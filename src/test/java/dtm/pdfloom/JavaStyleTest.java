package dtm.pdfloom;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

class JavaStyleTest {
    @Test
    void javaSourcesUseExplicitTypesSeparateAnnotationsAndMultilineBlocks() throws Exception {
        List<Path> files = new ArrayList<>();
        List<Path> directories =
                new ArrayList<>(
                        List.of(
                                Path.of("src/main/java"),
                                Path.of("src/test/java"),
                                Path.of("src/test/tools")));
        Path localReferences = Path.of("local-reference");
        if (Files.isDirectory(localReferences)) {
            try (Stream<Path> references = Files.list(localReferences)) {
                references
                        .filter(Files::isDirectory)
                        .sorted()
                        .forEach(
                                reference -> {
                                    directories.add(reference.resolve("java"));
                                    directories.add(reference.resolve("tools"));
                                });
            }
        }
        for (Path directory : directories) {
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(directory)) {
                paths.filter(path -> path.toString().endsWith(".java")).forEach(files::add);
            }
        }
        List<String> violations = new ArrayList<>();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager manager =
                compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            JavacTask task =
                    (JavacTask)
                            compiler.getTask(
                                    null,
                                    manager,
                                    null,
                                    List.of("-proc:none"),
                                    null,
                                    manager.getJavaFileObjectsFromPaths(files));
            Trees trees = Trees.instance(task);
            SourcePositions positions = trees.getSourcePositions();
            for (CompilationUnitTree unit : task.parse()) {
                String source = unit.getSourceFile().getCharContent(true).toString();
                String name = unit.getSourceFile().getName();
                Pattern tokens =
                        Pattern.compile(
                                "\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|//[^\\n"
                                    + "]*|/\\*[\\s\\S]*?\\*/");
                Matcher matcher = tokens.matcher(source);
                while (matcher.find()) {
                    if (matcher.group().startsWith("//") || matcher.group().startsWith("/*")) {
                        violations.add(name + ": code comment");
                    }
                }
                new TreeScanner<Void, Void>() {
                    @Override
                    public Void visitVariable(VariableTree variable, Void unused) {
                        int start = (int) positions.getStartPosition(unit, variable);
                        int end = (int) positions.getEndPosition(unit, variable);
                        if (start >= 0
                                && end >= 0
                                && source.substring(start, end)
                                        .matches("(?s)(?:final\\s+)?var\\s+.*")) {
                            violations.add(name + ": inferred local type");
                        }
                        return super.visitVariable(variable, unused);
                    }

                    @Override
                    public Void visitAnnotation(AnnotationTree annotation, Void unused) {
                        int end = (int) positions.getEndPosition(unit, annotation);
                        int newline = source.indexOf('\n', end);
                        if (end >= 0 && newline >= 0 && !source.substring(end, newline).isBlank()) {
                            violations.add(name + ": inline annotation");
                        }
                        return super.visitAnnotation(annotation, unused);
                    }

                    @Override
                    public Void visitBlock(BlockTree block, Void unused) {
                        int start = (int) positions.getStartPosition(unit, block);
                        int end = (int) positions.getEndPosition(unit, block);
                        if (start >= 0
                                && end >= 0
                                && !source.substring(start, end).contains("\n")) {
                            violations.add(name + ": inline block");
                        }
                        return super.visitBlock(block, unused);
                    }
                }.scan(unit, null);
            }
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }
}
