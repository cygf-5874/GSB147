import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * spscqueue 的固定验收程序（勿改）。
 *
 * <p>本题是「代码理解 / 审查」题，交付物是 {@code REVIEW.md}。本程序不执行缺陷实现的并发行为，
 * 而是校验审查结论的格式与落点：
 * <ul>
 *   <li>{@code review} 6：六个小节各 1，逐节核对「小节标题存在 + 触发条件/实际行为/根因/该怎么改
 *       四项俱全 + 至少一个 {@code SpscQueue.java:行区间} 落点 + 命中本节关键词集合」。</li>
 *   <li>{@code evidence} 2：① 引用的行区间必须落在 {@code SpscQueue.java} 的真实行范围内且起点是
 *       真实代码行；② 每个小节必须给出可复现的调用序列或 barrier 场景编号。</li>
 *   <li>{@code barrier} 2：check/ 自带的最小正确对照实现（{@code ReferenceImpl}）在栅栏 + {@code -Xint}
 *       下 200 轮「已发布前缀合法性」全过；且同一场景在 {@code -Xint} 下跨独立 JVM 能稳定复现。
 *       这两条只验证「判据本身可复现」，不读 SpscQueue.java，故起点状态即已通过。</li>
 * </ul>
 *
 * <p>输出格式：逐场景 {@code PASS <组>/<名>} 或 {@code FAIL <组>/<名>  期望=… 实际=…}；
 * 结尾 {@code 结果：通过 x/N}；全过 {@code exit 0}，否则 {@code exit 1}；
 * 支持 {@code -list} 与 {@code --only <组名>}；失败不早退。
 */
public final class Checker {

    private static final long WATCHDOG_SECONDS = 120L;

    /** 六个小节的标题（与 REVIEW.md 模板一一对应，顺序固定）。 */
    private static final String[] SECTION_TITLES = {
        "内存可见性与屏障",
        "批量发布的顺序",
        "满-空判定与序号回绕",
        "容量约束与下标换算",
        "阻塞等待与伪唤醒",
        "失败路径的副作用",
    };

    /** 每节必须命中的关键词集合（小写比对）。 */
    private static final String[][] SECTION_KEYWORDS = {
        {"acquire", "release", "volatile", "varhandle", "happens-before", "屏障", "内存可见", "可见性", "setrelease", "getacquire"},
        {"offerbatch", "发布", "批量", "前缀", "顺序", "tail", "先写", "setrelease"},
        {"size", "满", "空", "isfull", "isempty", "回绕", "取模", "%", "歧义", "capacity"},
        {"幂", "power", "capacity", "取模", "%", "下标", "2 的", "校验", "2的幂", "seq %"},
        {"pollblocking", "locksupport", "unpark", "park", "while", "伪唤醒", "复核", "if", "唤醒"},
        {"offer", "满", "false", "槽位", "污染", "副作用", "失败"},
    };

    /** 四个必须齐全的子字段（模板与答案都用这些词）。 */
    private static final String[] SUBFIELDS = {"触发条件", "实际行为", "根因", "该怎么改"};

    private static final Pattern LOCATION =
        Pattern.compile("(?:src/main/java/spscqueue/)?SpscQueue\\.java:(\\d+)(?:-(\\d+))?");

    private static final class Scenario {
        final String name;
        final String group;
        final Body body;

        Scenario(String group, String shortName, Body body) {
            this.group = group;
            this.name = group + "/" + shortName;
            this.body = body;
        }
    }

    private interface Body {
        /** 返回 null 表示 PASS，否则返回失败原因（已含 期望=/实际=）。 */
        String run() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        List<String> onlyGroups = new ArrayList<>();
        boolean list = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("-list".equals(a)) {
                list = true;
            } else if ("--only".equals(a)) {
                if (i + 1 >= args.length) {
                    System.out.println("--only 需要一个组名");
                    System.exit(2);
                }
                for (String g : args[++i].split(",")) {
                    if (!g.isEmpty()) {
                        onlyGroups.add(g);
                    }
                }
            } else {
                System.out.println("未知参数：" + a);
                System.exit(2);
            }
        }

        List<Scenario> all = scenarios();

        if (list) {
            for (Scenario s : all) {
                System.out.println(s.name);
            }
            return;
        }

        List<Scenario> selected = all;
        if (!onlyGroups.isEmpty()) {
            selected = new ArrayList<>();
            for (Scenario s : all) {
                if (onlyGroups.contains(s.group)) {
                    selected.add(s);
                }
            }
        }

        int passed = 0;
        for (Scenario s : selected) {
            String fail;
            try {
                fail = s.body.run();
            } catch (Throwable t) {
                fail = "期望=PASS 实际=异常(" + t.getClass().getSimpleName() + ":" + t.getMessage() + ")";
            }
            if (fail == null) {
                System.out.println("PASS " + s.name);
                passed++;
            } else {
                System.out.println("FAIL " + s.name + "  " + fail);
            }
        }
        int n = selected.size();
        System.out.println("结果：通过 " + passed + "/" + n);
        System.exit(passed == n ? 0 : 1);
    }

    private static List<Scenario> scenarios() {
        List<Scenario> list = new ArrayList<>();
        for (int k = 0; k < 6; k++) {
            final int idx = k;
            list.add(new Scenario("review", "sec" + (idx + 1), () -> checkReviewSection(idx)));
        }
        list.add(new Scenario("evidence", "lines", Checker::checkEvidenceLines));
        list.add(new Scenario("evidence", "calls", Checker::checkEvidenceCalls));
        list.add(new Scenario("barrier", "ref200", () -> runBarrier(200, 1)));
        list.add(new Scenario("barrier", "repro", () -> runBarrier(50, 3)));
        return list;
    }

    // ---------- 读取与解析 ----------

    private static List<String> readLines(String path) {
        Path p = Paths.get(path);
        if (!Files.exists(p)) {
            return null;
        }
        try {
            return Files.readAllLines(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** 把 REVIEW.md 解析成「标题 -> 正文」映射；正文为标题下一行到下一标题前的全部内容。 */
    private static List<String[]> parseSections(List<String> lines) {
        List<String[]> result = new ArrayList<>();
        int start = -1;
        String head = null;
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            String trimmed = raw.trim();
            if (trimmed.startsWith("##")) {
                if (start >= 0) {
                    result.add(new String[] {head, String.join("\n", lines.subList(start + 1, i))});
                }
                head = trimmed.replaceFirst("^#+\s*", "").trim();
                start = i;
            }
        }
        if (start >= 0) {
            result.add(new String[] {head, String.join("\n", lines.subList(start + 1, lines.size()))});
        }
        return result;
    }

    private static String findSectionBody(List<String[]> sections, String title) {
        for (String[] s : sections) {
            if (s[0].contains(title)) {
                return s[1];
            }
        }
        return null;
    }

    /** 子字段标签（如「触发条件」）后必须跟有非空白内容，才算已填写。 */
    private static boolean hasFilledField(String body, String field) {
        Pattern p = Pattern.compile(Pattern.quote(field) + "\\s*[:：]\\s*\\S");
        return p.matcher(body).find();
    }

    private static boolean isCodeLine(String line) {        String t = line.trim();
        if (t.isEmpty()) {
            return false;
        }
        if (t.startsWith("//") || t.startsWith("/*") || t.startsWith("*") || t.startsWith("*/")
                || t.startsWith("@") || t.startsWith("#")) {
            return false;
        }
        return true;
    }

    // ---------- review 6 ----------

    private static String checkReviewSection(int idx) throws Exception {
        List<String> review = readLines("REVIEW.md");
        if (review == null) {
            return "期望=小节齐全且四项俱全 实际=找不到 REVIEW.md";
        }
        List<String[]> sections = parseSections(review);
        String title = SECTION_TITLES[idx];
        String body = findSectionBody(sections, title);
        if (body == null) {
            return "期望=存在小节[" + title + "] 实际=未找到该小节标题";
        }
        // 四项子字段：标签后必须有实际内容（避免空模板的占位行被误判通过）
        for (String f : SUBFIELDS) {
            if (!hasFilledField(body, f)) {
                return "期望=小节含[" + f + "]且已填写 实际=子字段缺失或为空";
            }
        }
        // 落点：至少一个合法的 SpscQueue.java:行区间
        List<String> src = readLines("src/main/java/spscqueue/SpscQueue.java");
        if (src == null) {
            return "期望=能读取 SpscQueue.java 校验行区间 实际=找不到源文件";
        }
        int total = src.size();
        Matcher m = LOCATION.matcher(body);
        boolean foundValid = false;
        while (m.find()) {
            int l1 = Integer.parseInt(m.group(1));
            int l2 = m.group(2) == null ? l1 : Integer.parseInt(m.group(2));
            if (l1 < 1 || l2 < l1 || l2 > total) {
                return "期望=行区间落在[1," + total + "] 实际=越界(" + l1 + "-" + l2 + ")";
            }
            if (l1 <= src.size() && isCodeLine(src.get(l1 - 1))) {
                foundValid = true;
            }
        }
        if (!foundValid) {
            return "期望=至少一个真实代码行落点 实际=无有效 SpscQueue.java:行区间";
        }
        // 关键词命中
        String low = body.toLowerCase();
        boolean hit = false;
        for (String kw : SECTION_KEYWORDS[idx]) {
            if (low.contains(kw.toLowerCase())) {
                hit = true;
                break;
            }
        }
        if (!hit) {
            return "期望=命中本节关键词集合 实际=未命中(" + String.join("/", SECTION_KEYWORDS[idx]) + ")";
        }
        return null;
    }

    // ---------- evidence 2 ----------

    private static String checkEvidenceLines() throws Exception {
        List<String> review = readLines("REVIEW.md");
        if (review == null) {
            return "期望=有行区间落点 实际=找不到 REVIEW.md";
        }
        List<String> src = readLines("src/main/java/spscqueue/SpscQueue.java");
        if (src == null) {
            return "期望=可校验行区间 实际=找不到 SpscQueue.java";
        }
        int total = src.size();
        String all = String.join("\n", review);
        Matcher m = LOCATION.matcher(all);
        int valid = 0;
        while (m.find()) {
            int l1 = Integer.parseInt(m.group(1));
            int l2 = m.group(2) == null ? l1 : Integer.parseInt(m.group(2));
            if (l1 < 1 || l2 < l1 || l2 > total) {
                return "期望=行区间落在[1," + total + "] 实际=越界(" + l1 + "-" + l2 + ")";
            }
            if (l1 <= src.size() && isCodeLine(src.get(l1 - 1))) {
                valid++;
            } else {
                return "期望=行区间起点为真实代码行 实际=第" + l1 + "行非代码行";
            }
        }
        if (valid < 6) {
            return "期望=>=6 个有效行区间(每节一个) 实际=" + valid + " 个";
        }
        return null;
    }

    private static String checkEvidenceCalls() throws Exception {
        List<String> review = readLines("REVIEW.md");
        if (review == null) {
            return "期望=每节给出可复现调用序列 实际=找不到 REVIEW.md";
        }
        List<String[]> sections = parseSections(review);
        String[] calls = {"offer", "poll", "offerbatch", "pollblocking", "size", "stats",
            "isfull", "isempty"};
        String[] barrierTokens = {"barrier", "b1", "b2", "场景", "栅栏", "scenario"};
        for (int k = 0; k < 6; k++) {
            String body = findSectionBody(sections, SECTION_TITLES[k]);
            if (body == null) {
                return "期望=每节有可复现描述 实际=缺少小节[" + SECTION_TITLES[k] + "]";
            }
            String low = body.toLowerCase();
            boolean hasCall = false;
            for (String c : calls) {
                if (low.contains(c)) {
                    hasCall = true;
                    break;
                }
            }
            boolean hasBarrier = false;
            for (String b : barrierTokens) {
                if (low.contains(b)) {
                    hasBarrier = true;
                    break;
                }
            }
            if (!hasCall && !hasBarrier) {
                return "期望=第" + (k + 1) + "节给出调用序列或 barrier 场景编号 实际=两者皆无";
            }
        }
        return null;
    }

    // ---------- barrier 2 ----------

    private static String runBarrier(int rounds, int repeats) throws Exception {
        String outCheck = findOutCheck();
        String javaExe = System.getProperty("java.home") + java.io.File.separator + "bin"
            + java.io.File.separator + "java";
        for (int r = 0; r < repeats; r++) {
            String[] cmd = {javaExe, "-Xint", "-cp", outCheck, "ReferenceImpl",
                Integer.toString(rounds)};
            int code = runProcess(cmd, WATCHDOG_SECONDS);
            if (code == 2) {
                return "期望=对照实现在 -Xint 栅栏下稳定通过 实际=子进程超时";
            }
            if (code != 0) {
                return "期望=对照实现在 -Xint 栅栏下稳定通过 实际=子进程退出码=" + code;
            }
        }
        return null;
    }

    private static String findOutCheck() {
        String cp = System.getProperty("java.class.path", "");
        for (String entry : cp.split(java.io.File.pathSeparator)) {
            if (entry.endsWith("out-check")) {
                return entry;
            }
        }
        return "out-check";
    }

    private static int runProcess(String[] cmd, long timeoutSeconds) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return 2;
            }
            return p.exitValue();
        } catch (IOException e) {
            return 3;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 2;
        }
    }
}
