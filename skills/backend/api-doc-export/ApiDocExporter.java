import com.sun.source.doctree.DocCommentTree;
import com.sun.source.doctree.DocTree;
import com.sun.source.doctree.ParamTree;
import com.sun.source.doctree.TextTree;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WildcardTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

import javax.lang.model.element.Modifier;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * API 接口文档导出工具。
 * <p>读取 Spring MVC Controller 源码（Compiler Tree API），按 docs/API接口文档导出规范.md 导出 Markdown。</p>
 * <p>用法（必须在项目根目录执行，脚本路径换成实际安装位置）：</p>
 * <pre>java --source 21 &lt;本文件路径&gt; BaiJiaYunController</pre>
 * <p>参数可以是 Controller 类名、Java 源文件路径，也可以是非 Controller 的类（自动反查引用它的 Controller 一并导出）。
 * 导出目录依次取 {@code --out} 参数、仓库根 {@code .env.local} 里的 {@code api-doc-out}；
 * 两者都没有时报错退出。加 {@code --dry-run} 只打印解析结果、不写文件。</p>
 *
 * @author fengbo
 */
public class ApiDocExporter {

    public static void main(String[] args) throws Exception {
        // Windows 控制台默认 GBK，强制用 UTF-8 输出，避免中文告警与文件名乱码
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        Args a = Args.parse(args);
        Path root = Path.of(System.getProperty("user.dir"));
        Warnings warnings = new Warnings();
        SourceIndex index = new SourceIndex(root);
        String outSpec = a.out() != null ? a.out() : OutDir.resolve(root);
        if (outSpec == null && !a.dryRun()) {
            System.out.println("未配置导出目录：仓库根 " + OutDir.CONFIG_FILE + " 里没有 " + OutDir.CONFIG_KEY
                    + "。请在仓库根 %s 写入一行 %s=<导出目录绝对路径>".formatted(OutDir.CONFIG_FILE, OutDir.CONFIG_KEY));
            System.exit(1);
        }
        Path out = outSpec == null || a.dryRun() ? null : root.resolve(outSpec);
        if (out != null) {
            Files.createDirectories(out);
        }
        int exported = 0;
        Set<String> written = new HashSet<>();
        Set<String> targets = new LinkedHashSet<>();
        for (String arg : a.controllers()) {
            targets.addAll(index.resolveControllers(arg, warnings));
        }
        for (String name : targets) {
            Path file = index.resolveFile(name);
            if (file == null) {
                warnings.add("未找到类", name, "源码索引中没有该类");
                continue;
            }
            JavaSource.Unit unit = JavaSource.unit(file);
            System.out.println("[*] " + name + " -> " + root.relativize(file));
            ClassTree cls = unit.classes().get(name);
            if (cls == null) {
                warnings.add("未找到类", name, "文件内没有同名类声明");
                continue;
            }
            ControllerDoc doc = ControllerScanner.scan(unit, cls, warnings);
            if (out != null) {
                String fileName = doc.title() + ".md";
                if (!written.add(fileName)) {
                    warnings.add("文件名重复", name, fileName + " 已被同批次其他 Controller 占用，本次跳过");
                    continue;
                }
                List<RenderedHandler> rendered = HandlerRenderer.render(doc, unit, index, warnings);
                Path target = out.resolve(fileName);
                Files.writeString(target, MarkdownWriter.document(doc.title(), rendered), StandardCharsets.UTF_8);
                exported++;
                System.out.println("已导出 " + target.getFileName() + "（" + rendered.size() + " 个接口）");
                continue;
            }
            System.out.println("    标题: " + doc.title());
            System.out.println("    base: " + doc.basePath());
            int i = 0;
            for (HandlerDoc h : doc.handlers()) {
                i++;
                System.out.println("[" + i + "] " + h.methodName() + " " + h.httpMethod() + " " + h.path());
                System.out.println("    标题: " + h.title());
                System.out.println("    返回: " + Trees0.render(h.returnType()));
                if (!h.paramDocs().isEmpty()) {
                    System.out.println("    @param: " + h.paramDocs());
                }
                for (ParamDoc p : h.params()) {
                    System.out.println("    参数: " + p.annotations() + " " + p.name() + " : " + Trees0.render(p.type())
                            + (p.login() ? "  <登录上下文,丢弃>" : "")
                            + (p.requestBody() ? "  <请求体>" : "")
                            + (p.requestParam() ? "  <查询参数 名称=" + p.queryName() + " 必填=" + p.required() + ">" : "")
                            + (!p.login() && !p.requestBody() && !p.requestParam() ? "  <查询模型或标量>" : ""));
                }
            }
            System.out.println("    接口数: " + i);
        }
        for (String t : a.dumpTypes()) {
            dumpType(index, t, warnings);
        }
        if (out != null) {
            System.out.println("导出完成：" + exported + " 个文件");
        }
        warnings.report();
    }

    /** 打印某个模型类解析出的字段（顺序、类型、说明、初始值），用于排查字段相关差异。 */
    private static void dumpType(SourceIndex index, String simpleName, Warnings warnings) {
        Path file = index.resolveFile(simpleName);
        if (file == null) {
            warnings.add("未找到类", simpleName, "源码索引中没有该类");
            return;
        }
        JavaSource.Unit unit = JavaSource.unit(file);
        ClassTree cls = unit.classes().get(simpleName);
        if (cls == null) {
            warnings.add("未找到类", simpleName, "文件内没有同名类声明");
            return;
        }
        System.out.println("[*] " + simpleName + " -> " + file.getFileName());
        ModelInspector inspector = new ModelInspector(index, warnings);
        for (FieldNode f : inspector.fields(cls, unit, new ArrayList<>())) {
            System.out.println("    " + f.name() + " : " + Trees0.render(f.type())
                    + "  初始值=" + Trees0.render(f.initializer())
                    + "  说明=" + f.desc());
        }
    }
}

/** 命令行参数。 */
final class Args {
    private final List<String> controllers;
    private final List<String> dumpTypes;
    private final String out;
    private final boolean dryRun;

    private Args(List<String> controllers, List<String> dumpTypes, String out, boolean dryRun) {
        this.controllers = controllers;
        this.dumpTypes = dumpTypes;
        this.out = out;
        this.dryRun = dryRun;
    }

    static Args parse(String[] argv) {
        List<String> controllers = new ArrayList<>();
        List<String> dumpTypes = new ArrayList<>();
        String out = null;
        boolean dryRun = false;
        for (int i = 0; i < argv.length; i++) {
            String s = argv[i];
            if ("--out".equals(s)) {
                out = argv[++i];
            } else if ("--dump-type".equals(s)) {
                dumpTypes.add(argv[++i]);
            } else if ("--dry-run".equals(s)) {
                dryRun = true;
            } else if (s.startsWith("--")) {
                throw new IllegalArgumentException("未知参数: " + s);
            } else {
                controllers.add(s);
            }
        }
        return new Args(controllers, dumpTypes, out, dryRun);
    }

    List<String> controllers() {
        return controllers;
    }

    List<String> dumpTypes() {
        return dumpTypes;
    }

    String out() {
        return out;
    }

    boolean dryRun() {
        return dryRun;
    }
}

/** 导出目录解析：{@code --out} 参数 > 仓库根 {@code .env.local} 的 {@code api-doc-out}。 */
final class OutDir {
    static final String CONFIG_FILE = ".env.local";
    static final String CONFIG_KEY = "api-doc-out";

    private OutDir() {
    }

    /** 取仓库根 {@code .env.local} 里 {@code api-doc-out} 的值，没有返回 null。 */
    static String resolve(Path root) {
        Path config = root.resolve(CONFIG_FILE);
        if (!Files.isRegularFile(config)) {
            return null;
        }
        try {
            for (String raw : Files.readAllLines(config, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq < 0 || !CONFIG_KEY.equals(line.substring(0, eq).trim())) {
                    continue;
                }
                String value = line.substring(eq + 1).trim();
                return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                        ? value.substring(1, value.length() - 1)
                        : value;
            }
            return null;
        } catch (IOException e) {
            throw new IllegalStateException("读取导出目录配置失败: " + config, e);
        }
    }
}

/** 有序告警收集器，运行结束时统一打印。 */
final class Warnings {
    private final List<String> items = new ArrayList<>();

    void add(String kind, String ctx, String msg) {
        items.add("[" + kind + "] " + ctx + "  " + msg);
    }

    int count() {
        return items.size();
    }

    void report() {
        items.forEach(System.out::println);
        System.out.println("警告 " + items.size() + " 条");
    }
}

/** 全仓 Java 源码索引：全限定名 -> 文件，简单名 -> 文件列表。 */
final class SourceIndex {
    /** 扫描时跳过的目录：构建产物、依赖包。隐藏目录（.git、.idea、.agents 等）统一按前缀跳过。 */
    private static final Set<String> SKIP_DIRS = Set.of("target", "build", "out", "bin", "dist", "node_modules");
    /** 源码根目录的最大扫描深度，覆盖 modules/foo/src/main/java 这类布局。 */
    private static final int MAX_SCAN_DEPTH = 6;
    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    private final Map<String, Path> byFqn = new HashMap<>();
    private final Map<String, List<Path>> bySimple = new HashMap<>();
    private final List<Path> controllers = new ArrayList<>();

    SourceIndex(Path root) {
        List<Path> sourceRoots = sourceRoots(root);
        if (sourceRoots.isEmpty()) {
            throw new IllegalStateException("未找到任何 src/main/java 目录，请在项目根目录执行: " + root);
        }
        for (Path src : sourceRoots) {
            try (Stream<Path> walk = Files.walk(src)) {
                for (Path file : walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList())) {
                    String pkg = readPackage(file);
                    String simple = file.getFileName().toString().replace(".java", "");
                    byFqn.put(pkg.isEmpty() ? simple : pkg + "." + simple, file);
                    bySimple.computeIfAbsent(simple, k -> new ArrayList<>()).add(file);
                    if (simple.endsWith("Controller")) {
                        controllers.add(file);
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("扫描源码失败: " + src, e);
            }
        }
    }

    /** 收集项目下所有 {@code src/main/java} 目录，不依赖模块名，按路径排序保证索引稳定。 */
    private static List<Path> sourceRoots(Path root) {
        List<Path> result = new ArrayList<>();
        collectSourceRoots(root, 0, result);
        result.sort(Comparator.naturalOrder());
        return result;
    }

    /** 递归查找 {@code src/main/java}；命中 src 后不再向下展开，避免走进包目录。 */
    private static void collectSourceRoots(Path dir, int depth, List<Path> result) {
        if (depth > MAX_SCAN_DEPTH) {
            return;
        }
        try (DirectoryStream<Path> children = Files.newDirectoryStream(dir)) {
            for (Path child : children) {
                if (!Files.isDirectory(child)) {
                    continue;
                }
                String name = child.getFileName().toString();
                if (name.startsWith(".") || SKIP_DIRS.contains(name)) {
                    continue;
                }
                if ("src".equals(name)) {
                    Path java = child.resolve("main").resolve("java");
                    if (Files.isDirectory(java)) {
                        result.add(java);
                    }
                    continue;
                }
                collectSourceRoots(child, depth + 1, result);
            }
        } catch (IOException e) {
            throw new IllegalStateException("扫描源码目录失败: " + dir, e);
        }
    }

    private static String readPackage(Path file) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Matcher m = PACKAGE.matcher(text);
            return m.find() ? m.group(1) : "";
        } catch (IOException e) {
            return "";
        }
    }

    /** 按简单名唯一定位文件；多个候选时返回 null。 */
    Path resolveFile(String simpleName) {
        List<Path> hits = bySimple.get(simpleName);
        if (hits == null || hits.size() != 1) {
            return null;
        }
        return hits.get(0);
    }

    /** 按简单名定位文件；多个同名类时优先同包、其次同模块。 */
    Path resolveFile(String simpleName, JavaSource.Unit ctx) {
        List<Path> hits = bySimple.get(simpleName);
        if (hits == null) {
            return null;
        }
        if (hits.size() == 1) {
            return hits.get(0);
        }
        if (ctx == null) {
            return null;
        }
        for (Path hit : hits) {
            if (ctx.pkg().equals(readPackage(hit))) {
                return hit;
            }
        }
        Path module = moduleDir(ctx.file());
        for (Path hit : hits) {
            if (module != null && hit.startsWith(module)) {
                return hit;
            }
        }
        return null;
    }

    /** 取源文件所属模块目录（src 的上一层）。 */
    private static Path moduleDir(Path file) {
        Path p = file.getParent();
        while (p != null && p.getFileName() != null && !"src".equals(p.getFileName().toString())) {
            p = p.getParent();
        }
        return p == null ? null : p.getParent();
    }

    /**
     * 把一个命令行参数解析成要导出的 Controller 名。
     * 支持 Controller 类名、Java 源文件路径；非 Controller 的类反查引用它的所有 Controller。
     */
    List<String> resolveControllers(String arg, Warnings warnings) {
        String simple = arg;
        int sep = Math.max(simple.lastIndexOf('/'), simple.lastIndexOf('\\'));
        if (sep >= 0) {
            simple = simple.substring(sep + 1);
        }
        if (simple.endsWith(".java")) {
            simple = simple.substring(0, simple.length() - 5);
        }
        if (simple.endsWith("Controller")) {
            return List.of(simple);
        }
        List<String> hits = controllersUsing(simple);
        if (hits.isEmpty()) {
            warnings.add("未找到引用方", arg, "没有 Controller 引用 " + simple + "，跳过");
        }
        return hits;
    }

    /** 反查引用了某个类的 Controller：按源码文本做整词匹配，覆盖 import 与使用处。 */
    private List<String> controllersUsing(String simpleName) {
        Pattern word = Pattern.compile("\\b" + Pattern.quote(simpleName) + "\\b");
        List<String> hits = new ArrayList<>();
        for (Path file : controllers) {
            String text;
            try {
                text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("读取失败: " + file, e);
            }
            if (word.matcher(text).find()) {
                String fileName = file.getFileName().toString();
                hits.add(fileName.substring(0, fileName.length() - 5));
            }
        }
        return hits;
    }

    /**
     * 解析一个类型名到源码文件。
     * 顺序：全限定名直配 -> 同包 -> 显式 import -> 通配 import -> java.lang -> 全仓唯一直配。
     */
    Path resolve(String rawName, JavaSource.Unit context) {
        if (rawName == null || rawName.isEmpty()) {
            return null;
        }
        String name = rawName.replace(" ", "");
        if (name.contains(".")) {
            Path hit = byFqn.get(name);
            if (hit != null) {
                return hit;
            }
            String simple = name.substring(name.lastIndexOf('.') + 1);
            return resolveFile(simple);
        }
        if (context != null) {
            Path hit = byFqn.get(context.pkg().isEmpty() ? name : context.pkg() + "." + name);
            if (hit != null) {
                return hit;
            }
            for (String imp : context.imports()) {
                if (imp.endsWith("." + name)) {
                    Path p = byFqn.get(imp);
                    if (p != null) {
                        return p;
                    }
                } else if (imp.endsWith(".*")) {
                    String pkg = imp.substring(0, imp.length() - 2);
                    Path p = byFqn.get(pkg + "." + name);
                    if (p != null) {
                        return p;
                    }
                }
            }
        }
        return resolveFile(name, context);
    }
}

/** 单个源文件的解析门面：一次 JavacTask，提供语法树、DocTrees 与文件内所有类声明。 */
final class JavaSource {
    private static final JavaCompiler COMPILER = ToolProvider.getSystemJavaCompiler();
    private static final Map<Path, Unit> CACHE = new HashMap<>();

    private JavaSource() {
    }

    static Unit unit(Path file) {
        Unit cached = CACHE.get(file);
        if (cached != null) {
            return cached;
        }
        StandardJavaFileManager fm = COMPILER.getStandardFileManager(d -> { }, null, StandardCharsets.UTF_8);
        List<String> options = List.of("-proc:none", "-encoding", "UTF-8", "-nowarn", "-Xlint:none");
        JavacTask task = (JavacTask) COMPILER.getTask(null, fm, d -> { }, options, null, fm.getJavaFileObjects(file));
        Unit unit;
        try {
            Iterable<? extends CompilationUnitTree> units = task.parse();
            task.analyze();
            DocTrees docTrees = DocTrees.instance(task);
            Trees trees = Trees.instance(task);
            CompilationUnitTree tree = units.iterator().next();
            unit = new Unit(file, tree, docTrees, trees);
        } catch (Exception e) {
            throw new IllegalStateException("解析失败: " + file, e);
        }
        CACHE.put(file, unit);
        return unit;
    }

    /** 一个源文件的解析结果。 */
    static final class Unit {
        private final Path file;
        private final CompilationUnitTree tree;
        private final DocTrees docTrees;
        private final Trees trees;
        private final Map<String, ClassTree> classes = new LinkedHashMap<>();
        private final Map<Tree, TreePath> paths = new HashMap<>();
        private final List<String> imports = new ArrayList<>();

        Unit(Path file, CompilationUnitTree tree, DocTrees docTrees, Trees trees) {
            this.file = file;
            this.tree = tree;
            this.docTrees = docTrees;
            this.trees = trees;
            for (ImportTree t : tree.getImports()) {
                imports.add(t.getQualifiedIdentifier().toString());
            }
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitClass(ClassTree node, Void unused) {
                    classes.putIfAbsent(node.getSimpleName().toString(), node);
                    paths.putIfAbsent(node, getCurrentPath());
                    return super.visitClass(node, unused);
                }

                @Override
                public Void visitMethod(MethodTree node, Void unused) {
                    paths.putIfAbsent(node, getCurrentPath());
                    return super.visitMethod(node, unused);
                }

                @Override
                public Void visitVariable(VariableTree node, Void unused) {
                    paths.putIfAbsent(node, getCurrentPath());
                    return super.visitVariable(node, unused);
                }
            }.scan(tree, null);
        }

        Path file() {
            return file;
        }

        CompilationUnitTree tree() {
            return tree;
        }

        DocTrees docTrees() {
            return docTrees;
        }

        Trees trees() {
            return trees;
        }

        /** 文件内所有类（含嵌套类）的简单名映射。 */
        Map<String, ClassTree> classes() {
            return classes;
        }

        List<String> imports() {
            return imports;
        }

        /** 取某个语法树节点上的文档注释。 */
        DocCommentTree doc(Tree node) {
            TreePath path = paths.get(node);
            return path == null ? null : docTrees.getDocCommentTree(path);
        }

        String pkg() {
            return tree.getPackageName() == null ? "" : tree.getPackageName().toString();
        }
    }
}

/** Javadoc 读取：首段标题、@param 映射、字段说明。 */
final class JavadocReader {
    private static final String BR = "<br>";
    /** 标题结尾的标点与空白：中英文句号、逗号、分号、冒号、顿号、叹号、问号、省略号。 */
    private static final Pattern TRAILING_PUNCT = Pattern.compile("[\\s。，、；：！？…．,;:!?]+$");

    private JavadocReader() {
    }

    /** 取文档注释首段作为标题，并去掉结尾标点。 */
    static String title(DocCommentTree dc) {
        return TRAILING_PUNCT.matcher(firstParagraph(dc)).replaceAll("");
    }

    /** 取文档注释的首段（遇到空行即止），多行用 {@code <br>} 连接。 */
    static String firstParagraph(DocCommentTree dc) {
        List<String> lines = textLines(dc);
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            if (line.isEmpty()) {
                break;
            }
            out.add(line);
        }
        return String.join(BR, out);
    }

    /** 字段说明：去掉首尾空行后的全部文本，多行用 {@code <br>} 连接。 */
    static String description(DocCommentTree dc) {
        return String.join(BR, textLines(dc));
    }

    /** {@code @param} 名称到描述的映射。 */
    static Map<String, String> params(DocCommentTree dc) {
        Map<String, String> map = new LinkedHashMap<>();
        if (dc == null) {
            return map;
        }
        for (DocTree tag : dc.getBlockTags()) {
            if (tag instanceof ParamTree p) {
                map.put(p.getName().toString(), String.join(BR, textLines(p.getDescription())));
            }
        }
        return map;
    }

    private static List<String> textLines(DocCommentTree dc) {
        return dc == null ? List.of() : textLines(dc.getFullBody());
    }

    private static List<String> textLines(List<? extends DocTree> body) {
        List<String> lines = new ArrayList<>();
        for (DocTree t : body) {
            if (t instanceof TextTree text) {
                for (String line : text.getBody().split("\\R", -1)) {
                    lines.add(line.trim());
                }
            }
        }
        while (!lines.isEmpty() && lines.get(0).isEmpty()) {
            lines.remove(0);
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }
}

/** 语法树相关的静态工具：类型渲染、注解读取。 */
final class Trees0 {
    private Trees0() {
    }

    /** 类型语法树的简单名（去掉包名）。 */
    static String simpleOf(Tree type) {
        String s = render(type);
        return s.substring(s.lastIndexOf('.') + 1);
    }

    /** 把类型语法树渲染成可读字符串。 */
    static String render(Tree type) {
        if (type == null) {
            return "";
        }
        if (type instanceof ParameterizedTypeTree p) {
            return render(p.getType()) + "<" + p.getTypeArguments().stream().map(Trees0::render).collect(Collectors.joining(",")) + ">";
        }
        if (type instanceof ArrayTypeTree a) {
            return render(a.getType()) + "[]";
        }
        if (type instanceof WildcardTree) {
            return "?";
        }
        return type.toString();
    }

    /** 注解的简单名（去掉包名）。 */
    static String simpleName(AnnotationTree ann) {
        String s = ann.getAnnotationType().toString();
        return s.substring(s.lastIndexOf('.') + 1);
    }

    /** 取注解的第一个字符串字面量（value = "x" 或裸 "x"）。 */
    static String stringValue(AnnotationTree ann) {
        for (ExpressionTree e : ann.getArguments()) {
            ExpressionTree value = e;
            if (value instanceof AssignmentTree assign) {
                value = assign.getExpression();
            }
            if (value instanceof LiteralTree lit && lit.getValue() instanceof String s) {
                return s;
            }
        }
        return null;
    }

    /** 取注解上指定名称的布尔属性。 */
    static Boolean booleanValue(AnnotationTree ann, String name) {
        for (ExpressionTree e : ann.getArguments()) {
            if (e instanceof AssignmentTree assign && name.equals(assign.getVariable().toString())
                    && assign.getExpression() instanceof LiteralTree lit && lit.getValue() instanceof Boolean b) {
                return b;
            }
        }
        return null;
    }
}

/** 一个 Controller 的解析结果。 */
final class ControllerDoc {
    private final String title;
    private final String basePath;
    private final List<HandlerDoc> handlers;

    ControllerDoc(String title, String basePath, List<HandlerDoc> handlers) {
        this.title = title;
        this.basePath = basePath;
        this.handlers = handlers;
    }

    String title() {
        return title;
    }

    String basePath() {
        return basePath;
    }

    List<HandlerDoc> handlers() {
        return handlers;
    }
}

/** 一个接口的解析结果。 */
final class HandlerDoc {
    private final String methodName;
    private final String httpMethod;
    private final String path;
    private final String title;
    private final Map<String, String> paramDocs;
    private final List<ParamDoc> params;
    private final Tree returnType;

    HandlerDoc(String methodName, String httpMethod, String path, String title, Map<String, String> paramDocs,
               List<ParamDoc> params, Tree returnType) {
        this.methodName = methodName;
        this.httpMethod = httpMethod;
        this.path = path;
        this.title = title;
        this.paramDocs = paramDocs;
        this.params = params;
        this.returnType = returnType;
    }

    String methodName() {
        return methodName;
    }

    String httpMethod() {
        return httpMethod;
    }

    String path() {
        return path;
    }

    String title() {
        return title;
    }

    Map<String, String> paramDocs() {
        return paramDocs;
    }

    List<ParamDoc> params() {
        return params;
    }

    Tree returnType() {
        return returnType;
    }
}

/** 一个方法参数的解析结果。 */
final class ParamDoc {
    private final String name;
    private final Tree type;
    private final List<String> annotations;
    private final boolean login;
    private final boolean requestBody;
    private final boolean requestParam;
    private final String queryName;
    private final boolean required;

    ParamDoc(String name, Tree type, List<String> annotations, boolean login, boolean requestBody,
             boolean requestParam, String queryName, boolean required) {
        this.name = name;
        this.type = type;
        this.annotations = annotations;
        this.login = login;
        this.requestBody = requestBody;
        this.requestParam = requestParam;
        this.queryName = queryName;
        this.required = required;
    }

    String name() {
        return name;
    }

    Tree type() {
        return type;
    }

    List<String> annotations() {
        return annotations;
    }

    boolean login() {
        return login;
    }

    boolean requestBody() {
        return requestBody;
    }

    boolean requestParam() {
        return requestParam;
    }

    String queryName() {
        return queryName;
    }

    boolean required() {
        return required;
    }
}

/** 一个模型字段。 */
final class FieldNode {
    private final String name;
    private final Tree type;
    private final String desc;
    private final Tree initializer;

    FieldNode(String name, Tree type, String desc, Tree initializer) {
        this.name = name;
        this.type = type;
        this.desc = desc;
        this.initializer = initializer;
    }

    String name() {
        return name;
    }

    Tree type() {
        return type;
    }

    String desc() {
        return desc;
    }

    Tree initializer() {
        return initializer;
    }
}

/** Controller 扫描：按源码顺序提取接口与参数。 */
final class ControllerScanner {
    private static final Map<String, String> MAPPING_METHODS = Map.of(
            "GetMapping", "GET", "PostMapping", "POST", "RequestMapping", "REQUEST");

    private ControllerScanner() {
    }

    static ControllerDoc scan(JavaSource.Unit unit, ClassTree cls, Warnings warnings) {
        String basePath = "";
        for (AnnotationTree ann : cls.getModifiers().getAnnotations()) {
            if ("RequestMapping".equals(Trees0.simpleName(ann))) {
                basePath = Trees0.stringValue(ann);
                if (basePath == null) {
                    basePath = "";
                }
            }
        }
        String title = JavadocReader.title(unit.doc(cls));
        List<HandlerDoc> handlers = new ArrayList<>();
        for (Tree member : cls.getMembers()) {
            if (!(member instanceof MethodTree method)) {
                continue;
            }
            AnnotationTree mapping = null;
            for (AnnotationTree ann : method.getModifiers().getAnnotations()) {
                if (MAPPING_METHODS.containsKey(Trees0.simpleName(ann))) {
                    mapping = ann;
                    break;
                }
            }
            if (mapping == null) {
                continue;
            }
            if (!method.getModifiers().getFlags().contains(Modifier.PUBLIC)) {
                warnings.add("跳过方法", cls.getSimpleName() + "." + method.getName(), "非 public 方法，不导出");
                continue;
            }
            String httpMethod = MAPPING_METHODS.get(Trees0.simpleName(mapping));
            String seg = Trees0.stringValue(mapping);
            String path = joinPath(basePath, seg == null ? "" : seg);
            DocCommentTree dc = unit.doc(method);
            String handlerTitle = JavadocReader.title(dc);
            if (handlerTitle.isEmpty()) {
                handlerTitle = method.getName().toString();
                warnings.add("补默认标题", cls.getSimpleName() + "." + method.getName(), "无 Javadoc，标题回退为方法名");
            }
            List<ParamDoc> params = new ArrayList<>();
            for (VariableTree var : method.getParameters()) {
                params.add(readParam(unit, var));
            }
            handlers.add(new HandlerDoc(method.getName().toString(), httpMethod, path, handlerTitle,
                    JavadocReader.params(dc), params, method.getReturnType()));
        }
        return new ControllerDoc(title.isEmpty() ? cls.getSimpleName().toString() : title, basePath, handlers);
    }

    /**
     * 路径拼接：类级路径原样保留前导斜杠，只去掉结尾斜杠与方法值的开头斜杠。
     * 例：admin/bjy + room/list -> admin/bjy/room/list；/admin/volunteer/big + list -> /admin/volunteer/big/list
     */
    static String joinPath(String base, String seg) {
        String b = base == null ? "" : base;
        String s = seg == null ? "" : seg;
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        String path = b.isEmpty() ? s : b + "/" + s;
        return path.replace("//", "/");
    }

    private static ParamDoc readParam(JavaSource.Unit unit, VariableTree var) {
        List<String> annotations = new ArrayList<>();
        boolean login = false;
        boolean requestBody = false;
        boolean requestParam = false;
        String queryName = null;
        boolean required = false;
        for (AnnotationTree ann : var.getModifiers().getAnnotations()) {
            String simple = Trees0.simpleName(ann);
            annotations.add(simple);
            switch (simple) {
                case "Login" -> login = true;
                case "RequestBody" -> requestBody = true;
                case "RequestParam" -> {
                    requestParam = true;
                    String value = Trees0.stringValue(ann);
                    queryName = value != null ? value : var.getName().toString();
                    Boolean req = Trees0.booleanValue(ann, "required");
                    required = req == null || req;
                }
                default -> { }
            }
        }
        return new ParamDoc(var.getName().toString(), var.getType(), annotations, login, requestBody,
                requestParam, queryName, required);
    }
}

/** 模型字段收集：父类字段优先、按声明顺序、只取字段不取方法。 */
final class ModelInspector {
    private static final int MAX_DEPTH = 8;

    private final SourceIndex index;
    private final Warnings warnings;

    ModelInspector(SourceIndex index, Warnings warnings) {
        this.index = index;
        this.warnings = warnings;
    }

    /** 收集类的字段（父类在前），stack 用于防止继承环。 */
    List<FieldNode> fields(ClassTree cls, JavaSource.Unit unit, List<String> stack) {
        List<FieldNode> result = new ArrayList<>();
        String simpleName = cls.getSimpleName().toString();
        if (stack.contains(simpleName) || stack.size() > MAX_DEPTH) {
            return result;
        }
        stack.add(simpleName);
        Tree superclass = cls.getExtendsClause();
        if (superclass != null) {
            String superName = Trees0.render(superclass);
            if (!"Object".equals(superName)) {
                Path file = index.resolve(superName, unit);
                if (file != null) {
                    JavaSource.Unit superUnit = JavaSource.unit(file);
                    ClassTree superCls = superUnit.classes().get(superName.substring(superName.lastIndexOf('.') + 1));
                    if (superCls != null) {
                        result.addAll(fields(superCls, superUnit, stack));
                    }
                } else {
                    warnings.add("未解析类型", simpleName, "父类 " + superName + " 未找到源码");
                }
            }
        }
        for (Tree member : cls.getMembers()) {
            if (!(member instanceof VariableTree var)) {
                continue;
            }
            if (var.getModifiers().getFlags().contains(javax.lang.model.element.Modifier.STATIC)) {
                continue;
            }
            String desc = JavadocReader.description(unit.doc(var));
            result.add(new FieldNode(var.getName().toString(), var.getType(), desc, var.getInitializer()));
        }
        stack.remove(stack.size() - 1);
        return result;
    }
}

/** 文档节点：Markdown 表格与 JSON 示例共用的中间树，保证两者永不打架。 */
final class DocNode {
    private final String name;
    private final String type;
    private final String desc;
    private final String scalarDemo;
    private final List<DocNode> children;

    private DocNode(String name, String type, String desc, String scalarDemo, List<DocNode> children) {
        this.name = name;
        this.type = type;
        this.desc = desc;
        this.scalarDemo = scalarDemo;
        this.children = children;
    }

    /** 标量节点：整数、数字、布尔、字符串、日期。 */
    static DocNode scalar(String name, String type, String desc, String demo) {
        return new DocNode(name, type, desc == null ? "" : desc, demo, List.of());
    }

    /** 数组节点：children 只有一个元素节点（对象元素为 object，标量元素为标量）。 */
    static DocNode array(String name, String desc, DocNode element) {
        return new DocNode(name, "array", desc == null ? "" : desc, null, List.of(element));
    }

    /** 对象节点：children 为空时示例输出 null。 */
    static DocNode object(String name, String desc, List<DocNode> children) {
        return new DocNode(name, "object", desc == null ? "" : desc, null, children);
    }

    String name() {
        return name;
    }

    String type() {
        return type;
    }

    String desc() {
        return desc;
    }

    String scalarDemo() {
        return scalarDemo;
    }

    List<DocNode> children() {
        return children;
    }
}

/** Java 类型到文档类型的映射，以及各类文档类型的示例默认值。 */
final class TypeMapper {
    /** 需要展开为 array 的集合类型。 */
    static final Set<String> COLLECTIONS = Set.of("List", "ArrayList", "LinkedList", "Set", "HashSet",
            "LinkedHashSet", "TreeSet", "Collection", "Iterable");

    private static final Map<String, String> BASIC = new HashMap<>();

    static {
        for (String t : List.of("int", "Integer", "long", "Long", "short", "Short")) {
            BASIC.put(t, "integer");
        }
        for (String t : List.of("byte", "Byte", "float", "Float", "double", "Double", "BigDecimal")) {
            BASIC.put(t, "number");
        }
        for (String t : List.of("boolean", "Boolean")) {
            BASIC.put(t, "boolean");
        }
        for (String t : List.of("String", "char", "Character", "LocalDate", "LocalDateTime", "LocalTime",
                "Date", "Instant", "Timestamp", "BigInteger")) {
            BASIC.put(t, "string");
        }
    }

    private TypeMapper() {
    }

    /** 返回内置标量对应的文档类型，非标量返回 null。 */
    static String basic(String simpleName) {
        return BASIC.get(simpleName);
    }

    /** 文档类型的示例默认值。 */
    static String demoOf(String docType) {
        return switch (docType) {
            case "string" -> "\"\"";
            case "boolean" -> "false";
            default -> "0";
        };
    }
}

/** 把 Java 类型语法树展开成文档节点树。 */
final class NodeTreeBuilder {
    private static final int MAX_DEPTH = 8;

    private final SourceIndex index;
    private final Warnings warnings;

    NodeTreeBuilder(SourceIndex index, Warnings warnings) {
        this.index = index;
        this.warnings = warnings;
    }

    /** 构建请求体或响应 data 的节点树。 */
    DocNode build(String name, String desc, Tree type, JavaSource.Unit ctx) {
        return build(name, desc, type, ctx, new ArrayDeque<>());
    }

    private DocNode build(String name, String desc, Tree type, JavaSource.Unit ctx, Deque<String> stack) {
        if (type instanceof ArrayTypeTree arr) {
            return DocNode.array(name, desc, build("", "", arr.getType(), ctx, stack));
        }
        if (type instanceof ParameterizedTypeTree p) {
            String raw = Trees0.simpleOf(p.getType());
            if (TypeMapper.COLLECTIONS.contains(raw)) {
                return DocNode.array(name, desc, build("", "", p.getTypeArguments().get(0), ctx, stack));
            }
            return object(name, desc, p.getType(), ctx, stack);
        }
        if (type instanceof WildcardTree) {
            return DocNode.object(name, desc, List.of());
        }
        String simple = Trees0.simpleOf(type);
        String basic = TypeMapper.basic(simple);
        if (basic != null) {
            return DocNode.scalar(name, basic, desc, TypeMapper.demoOf(basic));
        }
        return object(name, desc, type, ctx, stack);
    }

    /** 解析到源码类并展开字段；解析不到或出现继承环时退化成无子节点的 object（示例为 null）。 */
    private DocNode object(String name, String desc, Tree type, JavaSource.Unit ctx, Deque<String> stack) {
        String simple = Trees0.simpleOf(type);
        if (stack.contains(simple) || stack.size() >= MAX_DEPTH) {
            return DocNode.object(name, desc, List.of());
        }
        // 泛型变量（T、E 等）没有可展开的字段，按空对象处理且不告警
        if (simple.length() == 1 && Character.isUpperCase(simple.charAt(0))) {
            return DocNode.object(name, desc, List.of());
        }
        // 优先在上下文文件内查找：嵌套静态类（如 QuizSettingResp.Quiz）不在全局索引里
        JavaSource.Unit unit = ctx;
        ClassTree cls = ctx.classes().get(simple);
        if (cls == null) {
            Path file = index.resolve(simple, ctx);
            if (file != null) {
                unit = JavaSource.unit(file);
                cls = unit.classes().get(simple);
            }
        }
        if (cls == null) {
            // 带外层类限定的嵌套类（如 CommResp.PageInfo）：先定位外层类再取内部类
            String raw = Trees0.render(type);
            if (raw.contains(".")) {
                String outer = raw.substring(0, raw.lastIndexOf('.'));
                outer = outer.substring(outer.lastIndexOf('.') + 1);
                Path outerFile = index.resolve(outer, ctx);
                if (outerFile != null) {
                    JavaSource.Unit outerUnit = JavaSource.unit(outerFile);
                    if (outerUnit.classes().containsKey(outer)) {
                        unit = outerUnit;
                        cls = outerUnit.classes().get(simple);
                    }
                }
            }
        }
        if (cls == null) {
            warnings.add("未解析类型", ctx.file().getFileName().toString(), simple + " 未找到源码，按 object 处理");
            return DocNode.object(name, desc, List.of());
        }
        if (cls.getKind() == Tree.Kind.ENUM) {
            return DocNode.scalar(name, "string", desc, "\"\"");
        }
        stack.push(simple);
        List<DocNode> children = new ArrayList<>();
        for (FieldNode f : new ModelInspector(index, warnings).fields(cls, unit, new ArrayList<>())) {
            DocNode child = build(f.name(), f.desc(), f.type(), unit, stack);
            Object init = f.initializer() instanceof LiteralTree lit ? lit.getValue() : null;
            if (init != null) {
                child = DocNode.scalar(child.name(), child.type(), child.desc(),
                        init instanceof String s ? "\"" + s + "\"" : String.valueOf(init));
            }
            children.add(child);
        }
        stack.pop();
        return DocNode.object(name, desc, children);
    }
}

/** 一条查询参数。 */
final class QueryRow {
    private final String name;
    private final boolean required;
    private final String desc;

    QueryRow(String name, boolean required, String desc) {
        this.name = name;
        this.required = required;
        this.desc = desc == null ? "" : desc;
    }

    String name() {
        return name;
    }

    boolean required() {
        return required;
    }

    String desc() {
        return desc;
    }
}

/** 一个接口渲染所需的全部数据。 */
final class RenderedHandler {
    private final String title;
    private final String path;
    private final String httpMethod;
    private final List<QueryRow> query;
    private final DocNode requestBody;
    private final boolean rawStringBody;
    private final DocNode data;

    RenderedHandler(String title, String path, String httpMethod, List<QueryRow> query, DocNode requestBody,
                    boolean rawStringBody, DocNode data) {
        this.title = title;
        this.path = path;
        this.httpMethod = httpMethod;
        this.query = query;
        this.requestBody = requestBody;
        this.rawStringBody = rawStringBody;
        this.data = data;
    }

    String title() {
        return title;
    }

    String path() {
        return path;
    }

    String httpMethod() {
        return httpMethod;
    }

    List<QueryRow> query() {
        return query;
    }

    DocNode requestBody() {
        return requestBody;
    }

    boolean rawStringBody() {
        return rawStringBody;
    }

    DocNode data() {
        return data;
    }
}

/** 把 Controller 的解析结果渲染成可写出的结构。 */
final class HandlerRenderer {
    /** 由框架注入、与请求参数无关的类型，一律不进文档。 */
    private static final Set<String> SKIP_TYPES = Set.of("HttpServletRequest", "HttpServletResponse",
            "HttpSession", "ServletRequest", "ServletResponse", "Model", "ModelMap", "BindingResult", "Errors",
            "RedirectAttributes", "Principal", "WebRequest", "MultipartFile");

    private HandlerRenderer() {
    }

    static List<RenderedHandler> render(ControllerDoc doc, JavaSource.Unit unit, SourceIndex index, Warnings warnings) {
        NodeTreeBuilder builder = new NodeTreeBuilder(index, warnings);
        List<RenderedHandler> result = new ArrayList<>();
        for (HandlerDoc h : doc.handlers()) {
            List<QueryRow> query = new ArrayList<>();
            DocNode requestBody = null;
            boolean rawStringBody = false;
            for (ParamDoc p : h.params()) {
                if (p.login() || SKIP_TYPES.contains(Trees0.simpleOf(p.type()))) {
                    continue;
                }
                String simple = Trees0.simpleOf(p.type());
                if (p.requestBody()) {
                    if ("String".equals(simple)) {
                        // 原始字符串请求体：只输出一行空名 + string，示例为空代码块
                        rawStringBody = true;
                        requestBody = DocNode.scalar("", "string", "", "\"\"");
                    } else {
                        requestBody = builder.build("", "", p.type(), unit);
                    }
                } else if (p.requestParam()) {
                    query.add(new QueryRow(p.queryName(), p.required(), descOf(h, p.name(), p.queryName())));
                } else if (TypeMapper.basic(simple) != null) {
                    query.add(new QueryRow(p.name(), false, descOf(h, p.name(), p.name())));
                } else {
                    query.addAll(modelRows(p, unit, index, h, warnings));
                }
            }
            result.add(new RenderedHandler(h.title(), h.path(), h.httpMethod(), query, requestBody, rawStringBody,
                    dataNode(h, unit, builder, warnings)));
        }
        return result;
    }

    /** 说明优先取方法 @param，其次取模型字段 Javadoc。 */
    private static String descOf(HandlerDoc h, String paramName, String queryName) {
        String desc = h.paramDocs().get(paramName);
        if (desc == null && !paramName.equals(queryName)) {
            desc = h.paramDocs().get(queryName);
        }
        return desc == null ? "" : desc;
    }

    /** 未标注注解的复杂参数：按查询模型展开字段，required 恒为 NO。 */
    private static List<QueryRow> modelRows(ParamDoc p, JavaSource.Unit unit, SourceIndex index, HandlerDoc h,
                                            Warnings warnings) {
        List<QueryRow> rows = new ArrayList<>();
        String simple = Trees0.simpleOf(p.type());
        Path file = index.resolve(simple, unit);
        JavaSource.Unit modelUnit = file == null ? null : JavaSource.unit(file);
        ClassTree cls = modelUnit == null ? null : modelUnit.classes().get(simple);
        if (cls == null) {
            warnings.add("未解析类型", unit.file().getFileName().toString(), "查询模型 " + simple + " 未找到源码");
            return rows;
        }
        for (FieldNode f : new ModelInspector(index, warnings).fields(cls, modelUnit, new ArrayList<>())) {
            String desc = h.paramDocs().getOrDefault(f.name(), f.desc());
            if (desc.isEmpty()) {
                desc = switch (f.name()) {
                    case "pageNum" -> "当前页码";
                    case "pageSize" -> "每页数量";
                    default -> "";
                };
            }
            rows.add(new QueryRow(f.name(), false, desc));
        }
        return rows;
    }

    /** 响应 data：从 CommResp 的泛型实参展开；非 CommResp 返回时退化为 object 并告警。 */
    private static DocNode dataNode(HandlerDoc h, JavaSource.Unit unit, NodeTreeBuilder builder, Warnings warnings) {
        Tree returnType = h.returnType();
        if (returnType instanceof ParameterizedTypeTree p && "CommResp".equals(Trees0.simpleOf(p.getType()))) {
            return builder.build("data", "数据", p.getTypeArguments().get(0), unit);
        }
        warnings.add("非 CommResp 返回", unit.file().getFileName() + "." + h.methodName(),
                "返回类型 " + Trees0.render(returnType) + "，data 按 object 处理");
        return DocNode.object("data", "数据", List.of());
    }
}

/** Markdown 输出：字节级复刻既有文档格式。 */
final class MarkdownWriter {
    private static final String NL = "\n";
    private static final String INDENT = "&ensp;&ensp;";
    private static final String GLYPH = "&#124;─";
    private static final String SEP = "------------";
    private static final String HEADER_ROW_4 = "| name | value | required | desc |";
    private static final String HEADER_ROW_3 = "| name | type | desc |";
    private static final String SEP_ROW_4 = "| " + SEP + " | " + SEP + " | " + SEP + " | " + SEP + " |";
    private static final String SEP_ROW_3 = "| " + SEP + " | " + SEP + " | " + SEP + " |";
    private static final String FENCE = "```";

    private MarkdownWriter() {
    }

    /** 生成一份 Controller 文档。 */
    static String document(String title, List<RenderedHandler> handlers) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append(NL).append(NL).append(title).append(NL).append(NL).append(NL);
        for (int i = 0; i < handlers.size(); i++) {
            RenderedHandler h = handlers.get(i);
            if (i > 0) {
                sb.append(NL);
            }
            sb.append("---").append(NL);
            sb.append("## ").append(h.title()).append(NL).append(NL);
            sb.append("> BASIC").append(NL).append(NL);
            sb.append("**Path:** ").append(h.path()).append(NL).append(NL);
            sb.append("**Method:** ").append(h.httpMethod()).append(NL).append(NL);
            sb.append("> REQUEST").append(NL).append(NL);
            if (h.requestBody() != null) {
                sb.append("**Headers:**").append(NL).append(NL);
                sb.append(HEADER_ROW_4).append(NL).append(SEP_ROW_4).append(NL);
                sb.append("| Content-Type | application/json | YES |  |").append(NL).append(NL);
            }
            if (!h.query().isEmpty()) {
                sb.append("**Query:**").append(NL).append(NL);
                sb.append(HEADER_ROW_4).append(NL).append(SEP_ROW_4).append(NL);
                for (QueryRow row : h.query()) {
                    sb.append("| ").append(row.name()).append(" |  | ").append(row.required() ? "YES" : "NO")
                            .append(" | ").append(row.desc()).append(" |").append(NL);
                }
                sb.append(NL);
            }
            if (h.requestBody() != null) {
                sb.append("**Request Body:**").append(NL).append(NL);
                sb.append(HEADER_ROW_3).append(NL).append(SEP_ROW_3).append(NL);
                if (h.rawStringBody()) {
                    sb.append("|  | string |  |").append(NL);
                } else {
                    for (DocNode child : h.requestBody().children()) {
                        appendRows(sb, child, 0);
                    }
                }
                sb.append(NL);
                sb.append("**Request Demo:**").append(NL).append(NL);
                sb.append(FENCE).append("json").append(NL);
                if (!h.rawStringBody()) {
                    appendJson(sb, h.requestBody(), 0);
                    sb.append(NL);
                }
                sb.append(FENCE).append(NL).append(NL);
            }
            sb.append("> RESPONSE").append(NL).append(NL);
            sb.append("**Body:**").append(NL).append(NL);
            sb.append(HEADER_ROW_3).append(NL).append(SEP_ROW_3).append(NL);
            sb.append("| code | integer | 状态码 |").append(NL);
            sb.append("| message | string | 状态码描述 |").append(NL);
            appendRows(sb, h.data(), 0);
            sb.append("| pageInfo | object | 分页信息 |").append(NL);
            sb.append("| ").append(INDENT).append(GLYPH).append("pageNum | integer | 当前页码 |").append(NL);
            sb.append("| ").append(INDENT).append(GLYPH).append("pageSize | integer | 每页数量 |").append(NL);
            sb.append("| ").append(INDENT).append(GLYPH).append("totalPage | integer | 总页数 |").append(NL);
            sb.append("| ").append(INDENT).append(GLYPH).append("totalRecord | integer | 总记录数 |").append(NL);
            sb.append(NL);
            sb.append("**Response Demo:**").append(NL).append(NL);
            sb.append(FENCE).append("json").append(NL);
            sb.append("{").append(NL);
            sb.append("  ").append(key("code")).append(": 0,").append(NL);
            sb.append("  ").append(key("message")).append(": \"\",").append(NL);
            sb.append("  ").append(key("data")).append(": ");
            appendJson(sb, h.data(), 2);
            sb.append(",").append(NL);
            sb.append("  ").append(key("pageInfo")).append(": {").append(NL);
            sb.append("    ").append(key("pageNum")).append(": 0,").append(NL);
            sb.append("    ").append(key("pageSize")).append(": 0,").append(NL);
            sb.append("    ").append(key("totalPage")).append(": 0,").append(NL);
            sb.append("    ").append(key("totalRecord")).append(": 0").append(NL);
            sb.append("  }").append(NL);
            sb.append("}").append(NL);
            sb.append(FENCE).append(NL);
        }
        return sb.toString();
    }

    /** 输出 JSON 的键，带两侧双引号。 */
    private static String key(String name) {
        return "\"" + name + "\"";
    }

    /** 递归输出节点表格行，level 为 0 时不加缩进前缀。 */
    private static void appendRows(StringBuilder sb, DocNode node, int level) {
        sb.append("| ");
        if (level > 0) {
            sb.append(INDENT.repeat(level)).append(GLYPH);
        }
        sb.append(node.name()).append(" | ").append(node.type()).append(" | ")
                .append(node.desc()).append(" |").append(NL);
        for (DocNode child : node.children()) {
            appendRows(sb, child, level + 1);
        }
    }

    /** 手写 JSON 示例：固定两空格缩进、固定键序、数组元素不展开。不使用 JsonUtils/ObjectMapper。 */
    private static void appendJson(StringBuilder sb, DocNode node, int indent) {
        if ("array".equals(node.type())) {
            DocNode element = node.children().isEmpty() ? null : node.children().get(0);
            sb.append("[").append(element == null || "object".equals(element.type()) ? "{}" : element.scalarDemo())
                    .append("]");
            return;
        }
        if ("object".equals(node.type())) {
            if (node.children().isEmpty()) {
                sb.append("null");
                return;
            }
            sb.append("{").append(NL);
            for (int i = 0; i < node.children().size(); i++) {
                DocNode child = node.children().get(i);
                sb.append(" ".repeat(indent + 2)).append(key(child.name())).append(": ");
                appendJson(sb, child, indent + 2);
                sb.append(i < node.children().size() - 1 ? "," : "").append(NL);
            }
            sb.append(" ".repeat(indent)).append("}");
            return;
        }
        sb.append(node.scalarDemo());
    }
}
