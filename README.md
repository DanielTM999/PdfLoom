# PdfLoom

Biblioteca Java 21 que converte HTML + CSS em PDF paginado. O parsing usa jsoup e ph-css, o layout e a paginação são próprios e a saída é gerada com Apache PDFBox. Não depende de navegador, serviço externo, executável ou Spring. Licença Apache 2.0.

> **Estado:** em desenvolvimento, já funcional para relatórios. Há testes de layout, paginação, tabelas, fontes e regressão visual. Não há promessa de compatibilidade integral com HTML/CSS: propriedades não suportadas são ignoradas e geram um diagnóstico.

- [Recursos principais](#recursos-principais)
- [Instalação](#instalação)
- [Uso rápido](#uso-rápido)
- [API](#api)
- [Configuração](#configuração)
- [Imagens, CSS e outros recursos](#imagens-css-e-outros-recursos)
- [Mídia paginada](#mídia-paginada)
- [Tabelas](#tabelas)
- [Fontes](#fontes)
- [Diagnósticos](#diagnósticos)
- [Uso em serviços e migração de outro renderizador](#uso-em-serviços-e-migração-de-outro-renderizador)
- [Segurança e limites](#segurança-e-limites)
- [Build e testes](#build-e-testes)
- [Licença](#licença)

## Recursos principais

- HTML tolerante (jsoup): não exige XHTML bem formado.
- CSS com cascata, especificidade, `!important`, `@import`, `@media print`, `@layer`, variáveis (`--x`/`var()`) e `calc()`.
- Layout em bloco, inline, `float`/`clear`, `position`, `flex`, `grid`, multicolunas, `transform` e `opacity`.
- Tabelas automáticas e fixas, `colspan`/`rowspan`, `border-collapse` e repetição de `thead`/`tfoot` a cada página.
- Mídia paginada: `@page` com tamanho, margens, páginas nomeadas e caixas de margem; `counter(page)`/`counter(pages)`, `running()`, `string-set`, `target-counter()` e quebras controladas.
- Sumário automático e destinos internos (links e marcadores no PDF).
- Imagens PNG, JPEG, GIF, BMP e SVG (rasterizado com Batik), inclusive em data URIs.
- Fontes TrueType, CFF e WOFF via `@font-face`, subconjuntadas e embutidas, com texto pesquisável.

## Instalação

### JitPack

A biblioteca é publicada pelo [JitPack](https://jitpack.io/#DanielTM999/PdfLoom) a partir das tags do repositório GitHub `DanielTM999/PdfLoom`. A versão da dependência é a **tag Git**. Adicione o repositório e a dependência ao `pom.xml` da aplicação:

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>com.github.DanielTM999</groupId>
        <artifactId>PdfLoom</artifactId>
        <version>1.0.0</version>
    </dependency>
</dependencies>
```

No Gradle:

```groovy
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.DanielTM999:PdfLoom:1.0.0'
}
```

Requer Java 21 ou superior. As dependências (jsoup, ph-css, PDFBox, Batik) chegam transitivamente pelo POM publicado. Para receber um JAR único com tudo embutido, use o classificador `all` (`<classifier>all</classifier>` no Maven ou `'com.github.DanielTM999:PdfLoom:1.0.0:all'` no Gradle). Nesse caso, a aplicação não deve declarar essas bibliotecas em outras versões.

### Instalação local

Para usar um checkout local, instale-o no repositório Maven da máquina:

```powershell
mvn clean install
```

Nesse caso, use as coordenadas do próprio `pom.xml`:

```xml
<dependency>
    <groupId>dtm.pdfloom</groupId>
    <artifactId>pdfloom</artifactId>
    <version>1.0.0</version>
</dependency>
```

As coordenadas do JitPack e as locais são diferentes; use uma delas em cada aplicação. O build local gera dois JARs:

| Arquivo | Uso |
|---|---|
| `target/pdfloom-1.0.0.jar` | Projetos Maven/Gradle; as dependências vêm do `pom.xml`. |
| `target/pdfloom-1.0.0-all.jar` | JAR único (Shade) com todas as dependências embutidas, para uso fora do Maven. Os pacotes não são renomeados; prefira o JAR comum quando o projeto já usa jsoup, ph-css, PDFBox ou Batik em outras versões. |

## Uso rápido

```java
import dtm.pdfloom.PdfConverter;
import dtm.pdfloom.impl.HtmlPdfConverter;

import java.nio.file.Path;

PdfConverter converter = new HtmlPdfConverter();

byte[] pdf = converter.convert("""
        <style>
            @page { size: A4; margin: 20mm; }
            h1 { color: #24646d; }
        </style>
        <h1>Relatório</h1>
        <p>Acentuação, símbolos e €.</p>
        """);

converter.convert(Path.of("templates/relatorio.html").toFile(), Path.of("relatorio.pdf"));
```

Para HTML gerado por um motor de templates, basta passar a `String` resultante para `convert`.

## API

`PdfConverter` tem seis métodos:

| Entrada | Retorna `byte[]` | Grava em arquivo |
|---|---|---|
| `String` com o HTML | `convert(String)` | `convert(String, Path)` |
| `File` | `convert(File)` | `convert(File, Path)` |
| `InputStream` | `convert(InputStream)` | `convert(InputStream, Path)` |

- Os construtores são `HtmlPdfConverter()`, `HtmlPdfConverter(ResourceResolver)` e `HtmlPdfConverter(PdfConversionConfig)`.
- Os streams recebidos **não** são fechados; tudo o que a biblioteca abre (fontes, recursos, documentos) é fechado por ela.
- Erros são lançados como `PdfConversionException` (não verificada), preservando a causa.
- Na gravação em arquivo, o destino só é substituído quando a conversão termina com sucesso, através de um temporário no mesmo diretório e movimento atômico quando disponível. O diretório de destino precisa existir.
- Uma instância de `PdfConversionConfig` é imutável e pode ser compartilhada entre threads; crie um `HtmlPdfConverter` por conversão ou reutilize-o sequencialmente.

## Configuração

```java
import dtm.pdfloom.config.PdfConversionConfig;
import dtm.pdfloom.impl.HtmlPdfConverter;

import java.nio.file.Path;

PdfConversionConfig config = PdfConversionConfig.builder()
        .baseDirectory(Path.of("templates"))
        .pageSize(595.2756, 841.8898)
        .margins(56.6929)
        .defaultFont("Roboto")
        .metadata("Relatório mensal", "Empresa", "Consultas")
        .missingResourcePolicy(PdfConversionConfig.MissingResourcePolicy.WARN_AND_SKIP)
        .diagnostics(d -> System.err.println(d.code() + ": " + d.message()))
        .build();

byte[] pdf = new HtmlPdfConverter(config).convert(html);
```

| Método do builder | Padrão | Descrição |
|---|---|---|
| `pageSize(w, h)`, `landscape()` | A4 retrato | Tamanho em pontos (1pt = 1/72 pol.). `@page { size }` no CSS tem prioridade. |
| `margins(all)` / `margins(t, r, b, l)` | 20 mm | Margens em pontos. `@page { margin }` tem prioridade. |
| `baseDirectory(Path)` / `baseUri(URI)` | — | Origem para referências relativas em HTML vindo de `String` ou `InputStream`. Para `File`, a origem é o próprio arquivo. |
| `resourceResolver(ResourceResolver)` | arquivos sob o diretório atual | Origem de imagens, CSS e fontes. Veja abaixo. |
| `allowLocalFiles()` | desligado | Permite ler arquivos locais por caminho absoluto. Veja abaixo. |
| `missingResourcePolicy(...)` | `FAIL` | `WARN_AND_SKIP` ignora recurso ausente ou imagem ilegível e gera um aviso, em vez de interromper a conversão. |
| `unsupportedCssPolicy(...)` | `WARN` | `FAIL` lança exceção ao encontrar CSS não suportado (útil em testes). |
| `remoteHosts(String...)`, `remoteTimeout(Duration)` | rede desligada | Libera HTTP/HTTPS apenas para os hosts listados. |
| `defaultFont(String)` | `Roboto` | Fonte usada quando nenhuma família declarada está disponível. |
| `font(family, weight, italic, ref)` | — | Registra uma fonte programaticamente. |
| `metadata(title, author, subject)` | — | Metadados do PDF. |
| `diagnostics(Consumer<Diagnostic>)` | `java.util.logging` | Recebe os avisos da conversão. |
| `charset(Charset)` | UTF-8 | Codificação de `File` e `InputStream`. |
| `maxInputBytes`, `maxResourceBytes`, `maxTotalResourceBytes`, `maxElements`, `maxDepth`, `maxPages`, `maxLayoutPasses` | limites seguros | Proteção contra entradas excessivas. |

## Imagens, CSS e outros recursos

`<img>`, `<link rel="stylesheet">`, `@import`, `url()` e `@font-face` passam pelo resolvedor configurado. Referências relativas dentro de um CSS usam a origem do próprio CSS. Data URIs (`data:image/png;base64,...`) funcionam sempre.

| Resolvedor | Uso |
|---|---|
| `FileSystemResourceResolver(Path raiz)` | Arquivos dentro de uma raiz. `/assets/x.png` é relativo à **raiz configurada**, não à raiz do disco. Bloqueia `..`, links simbólicos e caminhos fora da raiz. |
| `ClasspathResourceResolver(String raiz)` | Recursos empacotados no JAR da aplicação. |
| `MemoryResourceResolver(Map<String, byte[]>)` | Recursos em memória (testes, conteúdo gerado). |
| Implementação própria de `ResourceResolver` | Banco de dados, armazenamento em nuvem etc. Retorne um `ResolvedResource`; o stream pertence à conversão. |

### Arquivos locais por caminho absoluto

Templates que referenciam fotos ou anexos por caminho absoluto (`file:///D:/uploads/foto.png`, `file:///D:\uploads\foto.png` ou `D:\uploads\foto.png`) precisam de `allowLocalFiles()`. A opção é desligada por padrão porque permite que o HTML leia qualquer arquivo acessível ao processo; use-a apenas com HTML confiável, como o gerado pelo seu próprio servidor.

```java
PdfConversionConfig config = PdfConversionConfig.builder()
        .allowLocalFiles()
        .missingResourcePolicy(PdfConversionConfig.MissingResourcePolicy.WARN_AND_SKIP)
        .build();
```

Com essa configuração, caminhos com `\`, letra de unidade, espaços ou `%20` são aceitos. Uma foto ausente gera `MISSING_RESOURCE`, uma imagem corrompida gera `INVALID_RESOURCE`, e o PDF é gerado sem a imagem.

## Mídia paginada

```css
@page {
    size: A4;
    margin: 20mm 15mm 25mm;

    @top-center { content: element(cabecalho); }
    @bottom-right {
        content: "Página " counter(page) " de " counter(pages);
        font-size: 9pt;
    }
}

@page capa { margin: 0; }
.capa { page: capa; }

header.relatorio { position: running(cabecalho); }
h2 { string-set: secao content(text); break-after: avoid; }
section { break-before: page; }
tr, figure { break-inside: avoid; }
a.ref::after { content: " (página " target-counter(attr(href), page) ")"; }
```

- Caixas de margem: `@top-left`, `@top-center`, `@top-right`, `@bottom-left`, `@bottom-center` e `@bottom-right`.
- Conteúdo corrido: `position: running(nome)` com `element(nome)`, e `string-set` com `string(nome)`.
- Contadores: `counter(page)`, `counter(pages)`, contadores CSS próprios e `target-counter()` para referências cruzadas.
- Quebras: `break-before`/`break-after` (`page`, `left`, `right`, `avoid`), `break-inside: avoid`, `orphans` e `widows`.
- Sumário automático: inclua `<nav data-pdfloom-toc></nav>` no HTML.
- Unidades de viewport (`vw`, `vh`, `vmin`, `vmax`) não têm significado em PDF. Declarações que as usam são ignoradas com o aviso `UNSUPPORTED_VIEWPORT_UNIT`, e vale a regra anterior da cascata (normalmente o tamanho automático).
- Conteúdo que transborda lateralmente aparece na margem da folha, como nos navegadores; a separação entre páginas é sempre vertical.

### Número da página e total de páginas

O número só existe depois da paginação, por isso vem do CSS, pela propriedade `content`: `counter(page)` dá a página atual e `counter(pages)`, o total. Há três formas de usar.

**1. No cabeçalho ou rodapé da página**, nas caixas de margem do `@page`:

```css
@page {
    @bottom-right { content: "Página " counter(page) " de " counter(pages); }
}
```

**2. Em qualquer elemento do HTML**, com `::before` ou `::after`. A regra pode ficar num `<style>` dentro do próprio HTML:

```html
<style>
    .total::after { content: counter(pages); }
    .aqui::after  { content: counter(page); }
</style>

<p>Este documento tem <span class="total"></span> páginas.</p>
<p>Você está na página <span class="aqui"></span> de <span class="total"></span>.</p>
```

Para repetir esse texto em todas as páginas, use `position: fixed` no elemento, ou `position: running(nome)` com `content: element(nome)` numa caixa de margem.

**Atalho: atributo `content` no próprio elemento.** O valor do atributo é avaliado como o `content` do CSS e inserido no início do elemento, como um `::before` inline:

```html
<p content="'Página ' counter(page) ' de ' counter(pages)"></p>
<span content="counter(pages)"></span> páginas no total
<p data-secao="Anexo" content="attr(data-secao) ' - pág. ' counter(page)"></p>
```

A diferença para o texto escrito dentro da tag:

- **Atributo `content`:** o valor é processado. `counter(...)`, `counters(...)`, `attr(...)` e `target-counter(...)` são resolvidos, e o texto fixo precisa estar entre aspas (`'Página '`).
- **Texto dentro da tag:** é sempre literal. `<p>counter(page)</p>` imprime "counter(page)".
- **Os dois juntos:** o resultado do atributo vem antes do texto da tag.
- **Prioridade:** o atributo tem a prioridade de um estilo inline. Se o elemento também tiver uma regra `::before` com `content` no CSS, vale o atributo; as outras propriedades do `::before` (cor, fonte etc.) continuam sendo aplicadas.
- **`<meta content="...">`:** não é afetado.

**3. Página onde outro elemento está**, para sumários e "ver página X":

```html
<style>
    a.ref::after { content: " (página " target-counter(attr(href), page) ")"; }
</style>

<a class="ref" href="#anexo">Anexo</a>
<h2 id="anexo">Anexo</h2>
```

O que não funciona:

- escrever `counter(pages)` como texto dentro da tag, porque ele é impresso literalmente (use o atributo `content`);
- usar o atributo `style=""`, porque `::before`/`::after` exigem uma folha de estilo (`<style>` ou `.css`);
- preencher o número no Java ou no motor de templates, porque a paginação ainda não aconteceu.

## Tabelas

- `thead` e `tfoot` se repetem automaticamente em cada página da tabela (`display: table-header-group` / `table-footer-group`); não é preciso nenhuma propriedade proprietária.
- `table-layout: fixed` usa as larguras da primeira linha, de `col` e de `colgroup`; o layout automático respeita a largura mínima do conteúdo, inclusive com `white-space: nowrap`.
- Palavras podem quebrar após hífens entre letras (por exemplo, em UUIDs), como nos navegadores. Para permitir quebra em qualquer ponto, use `overflow-wrap: anywhere` ou `word-break: break-all`.
- Quando as larguras mínimas não cabem na página, o motor preserva o conteúdo e emite `TABLE_MIN_CONTENT_OVERFLOW` com a largura exigida e a disponível.
- Linhas com `break-inside: avoid` passam inteiras para a página seguinte; células mais altas que uma página continuam em limites de linha de texto, sem cortar caracteres.
- `border-collapse: collapse` segue o modelo do CSS 2.1: a borda vencedora de cada aresta é desenhada uma vez, e o conteúdo é recuado em metade dela.

## Fontes

| Família declarada | Fonte usada (embutida no JAR) |
|---|---|
| `Arial`, `Helvetica`, `sans-serif` | Liberation Sans (mesmas métricas do Arial) |
| `Times`, `Times New Roman`, `serif` | Liberation Serif |
| `Courier`, `Courier New`, `monospace` | Liberation Mono |
| outras sem `@font-face` | `defaultFont` (Roboto) |

Uma fonte declarada com `@font-face` ou registrada com `.font(...)` tem prioridade sobre essas substituições:

```css
@font-face {
    font-family: "Inter";
    src: url("fonts/Inter-Regular.woff") format("woff");
    font-weight: 400;
}
body { font-family: "Inter", Arial, sans-serif; }
```

São aceitos TrueType, CFF/OpenType e WOFF. As fontes são subconjuntadas e embutidas, e o texto do PDF continua pesquisável e copiável. Texto complexo (árabe, bidirecional, marcas combinantes) usa shaping em processo. Glifos ausentes em todas as fontes disponíveis geram um diagnóstico.

## Diagnósticos

A conversão não falha por CSS desconhecido: ela registra um aviso e segue. Alguns códigos comuns:

| Código | Significado |
|---|---|
| `UNSUPPORTED_PROPERTY` | Propriedade não suportada, ignorada. |
| `UNSUPPORTED_VIEWPORT_UNIT` | Declaração com `vw`/`vh` ignorada. |
| `MISSING_RESOURCE` / `INVALID_RESOURCE` | Recurso ausente ou ilegível (com `WARN_AND_SKIP`). |
| `TABLE_MIN_CONTENT_OVERFLOW` | O conteúdo mínimo da tabela é mais largo que o espaço disponível. |
| `TEXT_OVERFLOW` | Palavra sem ponto de quebra é mais larga que a linha. |
| `AUTO_HEIGHT_CLIP` | `overflow: hidden` com altura automática; o recorte usa a altura do conteúdo. |
| `MISSING_ANCHOR` | Link interno para um `id` inexistente. |

Em testes automatizados, `unsupportedCssPolicy(FAIL)` transforma os avisos `UNSUPPORTED_*` em erro.

## Uso em serviços e migração de outro renderizador

Quando o HTML já é gerado por outro renderizador HTML → PDF, em geral basta passar o mesmo HTML para o PdfLoom. Uma implementação típica num serviço:

```java
public final class PdfLoomRenderer {
    private final PdfConversionConfig config = PdfConversionConfig.builder()
            .allowLocalFiles()
            .missingResourcePolicy(PdfConversionConfig.MissingResourcePolicy.WARN_AND_SKIP)
            .diagnostics(d -> log.warn("{}: {}", d.code(), d.message()))
            .build();

    public byte[] render(String html) {
        return new HtmlPdfConverter(config).convert(html);
    }
}
```

Diferenças a observar:

- **XHTML:** não é necessário. O HTML é interpretado como num navegador.
- **Propriedades proprietárias** (prefixos de fornecedor): são ignoradas com aviso. A repetição de cabeçalhos de tabela já é automática.
- **Imagens por caminho absoluto:** ligue `allowLocalFiles()`.
- **Erros:** o PdfLoom lança `PdfConversionException`. Trate a exceção se o serviço precisar devolver uma resposta vazia em caso de falha.
- **`px`:** vale 0,75pt (1/96 pol.), como no CSS.
- **Unidades de viewport:** `vw`/`vh` são ignoradas, como explicado acima.
- **Fontes:** Arial/Helvetica/Courier usam as substitutas Liberation, de mesmas métricas. Pequenas diferenças de rasterização são esperadas.
- **Validação:** ao substituir outro renderizador, compare os PDFs de cada tipo de relatório usando dados reais antes de trocar em produção.

## Segurança e limites

- Rede desligada por padrão; quando liberada, só os hosts exatos de `remoteHosts`, inclusive em redirecionamentos.
- O resolvedor de arquivos bloqueia saída da raiz e links simbólicos externos. `allowLocalFiles()` remove essa restrição para caminhos absolutos e deve ser usado apenas com HTML confiável.
- SVG é rasterizado sem scripts e sem acesso externo fora do resolvedor.
- Limites configuráveis de bytes, elementos, profundidade, páginas e passes de layout.

## Build e testes

```powershell
mvn clean verify
```

- Requer JDK 21 ou superior e Maven 3.9+.
- A suíte inclui testes de layout, paginação, tabelas, fontes, recursos, desempenho e regressão visual contra imagens revisadas em `src/test/resources/visual`. Uma mudança visual que exceda a tolerância exige revisão e atualização explícita dessas imagens.
- `JavaStyleTest` verifica o estilo do código: tipos explícitos (sem `var`), quatro espaços, anotações em linhas próprias, blocos em várias linhas e nenhum comentário.

Se a IDE compilar ao mesmo tempo que o Maven, use o perfil isolado. Os artefatos ficam em `target/isolated`:

```powershell
mvn -Pisolated-build clean verify
```

Exemplos e auditoria da distribuição:

```powershell
powershell -NoProfile -File src/test/tools/Generate-Reports.ps1
powershell -NoProfile -File src/test/tools/Verify-Distribution.ps1
```

- O primeiro script gera PDFs de exemplo em `src/test/output/reports` (pasta ignorada pelo Git), com um índice em HTML.
- O segundo valida os dois JARs: recursos, avisos de licença, ausência de classes de teste e smoke test de conversão. O exemplo de código está em [Report.java](src/test/java/dtm/pdfloom/examples/Report.java), e os HTMLs em `src/test/resources/examples`.

### Referências locais opcionais

A pasta `local-reference/` é ignorada pelo Git e não é necessária para compilar ou usar o PdfLoom. O perfil `-Plocal-reference` adiciona aos testes as pastas `java/` e `resources/` de uma referência local, indicada por `-Dlocal.reference.root=<pasta>` ou por um `.mvn/maven.config` local, também ignorado. O Enforcer avisa quando as pastas não existem.

## Licença

Apache License 2.0; veja [LICENSE](LICENSE) e [NOTICE](NOTICE). As dependências de runtime e suas licenças estão inventariadas em [DEPENDENCIES.json](DEPENDENCIES.json), e os avisos originais são empacotados em `META-INF/pdfloom/licenses`. Nenhuma dependência de runtime usa GPL, LGPL ou AGPL. As fontes Roboto (Apache 2.0) e Liberation 2.1.5 (OFL-1.1) são distribuídas sem modificação.
