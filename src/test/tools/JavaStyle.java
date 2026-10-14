import com.sun.source.tree.*;
import com.sun.source.util.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import javax.lang.model.element.Element;
import javax.lang.model.type.TypeMirror;
import javax.tools.*;

public class JavaStyle {
    record Edit(int start, int end, String text) {
    }

    public static void main(String[] arguments) throws IOException {
        List<Path> files = new ArrayList<>();
        for (String directory : List.of("src/main/java", "src/test/java", "src/test/tools")) {
            try (java.util.stream.Stream<Path> paths = Files.walk(Path.of(directory))) {
                paths.filter(path -> path.toString().endsWith(".java")).forEach(files::add);
            }
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager manager =
                compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            JavacTask task =
                    (JavacTask)
                            compiler.getTask(
                                    null,
                                    manager,
                                    diagnostic -> {
                                        if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                                            System.err.println(diagnostic);
                                        }
                                    },
                                    List.of("-proc:none", "-classpath", arguments[0]),
                                    null,
                                    manager.getJavaFileObjectsFromPaths(files));
            List<CompilationUnitTree> units = new ArrayList<>();
            task.parse().forEach(units::add);
            Trees trees = Trees.instance(task);
            SourcePositions positions = trees.getSourcePositions();
            Map<CompilationUnitTree, List<TreePath>> inferred = new IdentityHashMap<>();
            for (CompilationUnitTree unit : units) {
                String source = unit.getSourceFile().getCharContent(true).toString();
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitVariable(VariableTree variable, Void unused) {
                        long start = positions.getStartPosition(unit, variable);
                        long end = positions.getEndPosition(unit, variable);
                        if (start >= 0 && end >= 0) {
                            String declaration = source.substring((int) start, (int) end);
                            if (declaration.matches("(?s)(?:final\\s+)?var\\s+.*")) {
                                inferred.computeIfAbsent(unit, key -> new ArrayList<>())
                                        .add(getCurrentPath());
                            }
                        }
                        return super.visitVariable(variable, unused);
                    }
                }.scan(unit, null);
            }
            task.analyze();
            int count = 0;
            for (CompilationUnitTree unit : units) {
                Path file = Path.of(unit.getSourceFile().toUri());
                String source = Files.readString(file);
                List<Edit> edits = new ArrayList<>();
                for (TreePath path : inferred.getOrDefault(unit, List.of())) {
                    VariableTree variable = (VariableTree) path.getLeaf();
                    int start = (int) positions.getStartPosition(unit, variable);
                    int token = source.indexOf("var", start);
                    Element element = trees.getElement(path);
                    TypeMirror type = element.asType();
                    String explicit = type.toString();
                    if (explicit.contains("<anonymous")) {
                        NewClassTree creation = (NewClassTree) variable.getInitializer();
                        explicit = creation.getIdentifier().toString();
                    }
                    if (explicit.contains("capture#")) {
                        throw new IllegalStateException(
                                "Captured type in " + file + ": " + explicit);
                    }
                    edits.add(new Edit(token, token + 3, explicit));
                    count++;
                }
                new TreeScanner<Void, Void>() {
                    private void block(StatementTree statement) {
                        if (statement == null || statement instanceof BlockTree) {
                            return;
                        }
                        int start = (int) positions.getStartPosition(unit, statement);
                        int end = (int) positions.getEndPosition(unit, statement);
                        if (start >= 0 && end >= 0) {
                            edits.add(new Edit(start, start, "{\n"));
                            edits.add(new Edit(end, end, "\n}"));
                        }
                    }

                    @Override
                    public Void visitIf(IfTree statement, Void unused) {
                        block(statement.getThenStatement());
                        if (!(statement.getElseStatement() instanceof IfTree)) {
                            block(statement.getElseStatement());
                        }
                        return super.visitIf(statement, unused);
                    }

                    @Override
                    public Void visitForLoop(ForLoopTree statement, Void unused) {
                        block(statement.getStatement());
                        return super.visitForLoop(statement, unused);
                    }

                    @Override
                    public Void visitEnhancedForLoop(EnhancedForLoopTree statement, Void unused) {
                        block(statement.getStatement());
                        return super.visitEnhancedForLoop(statement, unused);
                    }

                    @Override
                    public Void visitWhileLoop(WhileLoopTree statement, Void unused) {
                        block(statement.getStatement());
                        return super.visitWhileLoop(statement, unused);
                    }
                }.scan(unit, null);
                edits.sort(Comparator.comparingInt(Edit::start).reversed());
                StringBuilder updated = new StringBuilder(source);
                for (Edit edit : edits) {
                    updated.replace(edit.start(), edit.end(), edit.text());
                }
                Files.writeString(file, stripComments(updated.toString()));
            }
            System.out.println("Explicit types: " + count + "; files: " + units.size());
        }
    }

    private static String stripComments(String source) {
        StringBuilder output = new StringBuilder();
        int index = 0;
        while (index < source.length()) {
            char current = source.charAt(index);
            if (source.startsWith("\"\"\"", index)) {
                int end = source.indexOf("\"\"\"", index + 3);
                if (end < 0) {
                    throw new IllegalArgumentException("Unclosed text block");
                }
                output.append(source, index, end + 3);
                index = end + 3;
            } else if (current == '"' || current == '\'') {
                char delimiter = current;
                output.append(current);
                index++;
                while (index < source.length()) {
                    char next = source.charAt(index++);
                    output.append(next);
                    if (next == '\\' && index < source.length()) {
                        output.append(source.charAt(index++));
                    } else if (next == delimiter) {
                        break;
                    }
                }
            } else if (source.startsWith("//", index)) {
                while (index < source.length() && source.charAt(index) != '\n') {
                    index++;
                }
            } else if (source.startsWith("/*", index)) {
                int end = source.indexOf("*/", index + 2);
                if (end < 0) {
                    throw new IllegalArgumentException("Unclosed comment");
                }
                for (int cursor = index; cursor < end + 2; cursor++) {
                    output.append(source.charAt(cursor) == '\n' ? '\n' : ' ');
                }
                index = end + 2;
            } else {
                output.append(current);
                index++;
            }
        }
        return output.toString();
    }
}
