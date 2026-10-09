package io.github.liquiHg.hexencrypt.integration;

import at.petrak.hexcasting.api.casting.math.HexAngle;
import at.petrak.hexcasting.api.casting.math.HexDir;
import io.github.liquiHg.hexencrypt.HexEncrypt;

import java.util.*;

/**
 * 诊断 hexencrypt:iss_devour 的图案生成过程。
 * 调用 {@link #debugDevour()} 即可在日志中输出逐步追踪。
 *
 * 使用固定随机种子，保证每次运行的追踪结果一致、可复现。
 */
public final class ISSHexDebug {

    private static final HexDir[] ALL_DIRS = {
            HexDir.NORTH_EAST, HexDir.EAST, HexDir.SOUTH_EAST,
            HexDir.SOUTH_WEST, HexDir.WEST, HexDir.NORTH_WEST
    };
    private static final int[][] DIR_DELTAS = {
            { 1, -1}, { 1,  0}, { 0,  1},
            {-1,  1}, {-1,  0}, { 0, -1}
    };

    // 与 ISSHexIntegration 保持一致的段位常量
    private static final String SCHOOL_BLOOD = "edewqaqqqeeeeqeweweweweweddw";
    private static final String TARGET_OTHER = "ede";
    private static final String CAST_INSTANT = "www";

    /** 固定种子，便于复现。 */
    private static final Random RNG = new Random(0xDEADBEEFL);

    private ISSHexDebug() {}

    // =====================================================================
    // 对外入口
    // =====================================================================

    public static void debugDevour() {
        String spellName = "devour";
        String suffix = hashSuffix(spellName, 6);

        log("================================================================");
        log("ISSHexDebug: iss_devour 拼接过程诊断");
        log("  school = " + SCHOOL_BLOOD + "  (len=" + SCHOOL_BLOOD.length() + ")");
        log("  target = " + TARGET_OTHER);
        log("  cast   = " + CAST_INSTANT);
        log("  suffix = " + suffix);
        log("================================================================");

        // ---------- 场景 A：B 阶段，connLen = 1~5（当前代码行为） ----------
        log("");
        log("### 场景 A：阶段 B，connLen ∈ [1,5]（当前代码行为）");
        log("    段序列 = {ede, www, " + suffix + "}");
        for (int trial = 0; trial < 5; trial++) {
            log("");
            log("--- trial #" + trial + " ---");
            boolean ok = runOneTrial(
                    new String[]{ TARGET_OTHER, CAST_INSTANT, suffix },
                    HexDir.NORTH_EAST,
                    /*connLenOverride*/ -1);
            if (ok) { log(">>> trial #" + trial + " 成功"); break; }
        }

        // ---------- 场景 B：B 阶段，connLen = 0（无连接段） ----------
        log("");
        log("### 场景 B：阶段 B，connLen = 0（无连接段，直接拼接）");
        log("    段序列 = {ede, www, " + suffix + "}");
        log("");
        boolean okB = runOneTrial(
                new String[]{ TARGET_OTHER, CAST_INSTANT, suffix },
                HexDir.NORTH_EAST,
                /*connLenOverride*/ 0);
        log(okB ? ">>> 场景 B 成功" : ">>> 场景 B 失败");

        // ---------- 场景 C：A 阶段，connLen = 0 ----------
        log("");
        log("### 场景 C：阶段 A，connLen = 0");
        log("    段序列 = {SCHOOL_BLOOD, ede, www, " + suffix + "}");
        log("");
        boolean okC = runOneTrial(
                new String[]{ SCHOOL_BLOOD, TARGET_OTHER, CAST_INSTANT, suffix },
                HexDir.NORTH_EAST,
                /*connLenOverride*/ 0);
        log(okC ? ">>> 场景 C 成功" : ">>> 场景 C 失败");

        // ---------- 场景 D：A 阶段，connLen = 1~5 ----------
        log("");
        log("### 场景 D：阶段 A，connLen ∈ [1,5]");
        log("    段序列 = {SCHOOL_BLOOD, ede, www, " + suffix + "}");
        log("");
        boolean okD = runOneTrial(
                new String[]{ SCHOOL_BLOOD, TARGET_OTHER, CAST_INSTANT, suffix },
                HexDir.NORTH_EAST,
                /*connLenOverride*/ -1);
        log(okD ? ">>> 场景 D 成功" : ">>> 场景 D 失败");

        log("");
        log("================================================================");
        log("诊断完成。对比场景 A/B 的结果即可看出 connLen=0 的价值。");
        log("================================================================");
    }

    // =====================================================================
    // 单次试跑：逐步打印
    // =====================================================================

    /**
     * @param connLenOverride -1 = 随机 1~5；>=0 = 强制使用该值
     */
    private static boolean runOneTrial(String[] segments, HexDir startDir,
                                       int connLenOverride) {
        Stroker s = new Stroker(startDir);

        for (int segIdx = 0; segIdx < segments.length; segIdx++) {
            String seg = segments[segIdx];

            // ---------- 连接段 ----------
            if (segIdx > 0) {
                int connLen = (connLenOverride >= 0)
                        ? connLenOverride
                        : 1 + RNG.nextInt(5);
                log(String.format("  [conn] %d 步连接 (pos=%s, compass=%s)",
                        connLen, coord(s), s.compass));
                for (int i = 0; i < connLen; i++) {
                    List<HexDir> valid = new ArrayList<>(6);
                    for (HexDir d : ALL_DIRS) {
                        if (s.canAppend(d)) valid.add(d);
                    }
                    if (valid.isEmpty()) {
                        log(String.format("    step %d: 无合法方向 → 连接失败", i));
                        return false;
                    }
                    HexDir chosen = pickMostSpacious(s, valid);
                    log(String.format("    step %d: valid=%s → 选 %s",
                            i, dirShort(valid), chosen));
                    s.append(chosen);
                }
                log(String.format("  [conn] 结束 (pos=%s, compass=%s)",
                        coord(s), s.compass));
            }

            // ---------- 官能团 ----------
            log(String.format("  [seg %d] '%s' 起始 (pos=%s, compass=%s)",
                    segIdx, seg, coord(s), s.compass));
            for (int i = 0; i < seg.length(); i++) {
                char c = seg.charAt(i);
                HexAngle a = HexAngle.Companion.fromChar(c);
                if (a == null) {
                    log(String.format("    [%d]'%c' 非法字符", i, c));
                    return false;
                }
                HexDir nd = s.compass.rotatedBy(a);
                if (nd == s.compass.rotatedBy(HexAngle.BACK)) {
                    log(String.format("    [%d]'%c' 回退 (compass=%s, newDir=%s)",
                            i, c, s.compass, nd));
                    return false;
                }
                if (!s.canAppend(nd)) {
                    log(String.format("    [%d]'%c' 边重复 (compass=%s, newDir=%s, pos=%s)",
                            i, c, s.compass, nd, coord(s)));
                    return false;
                }
                s.append(nd);
                log(String.format("    [%d]'%c' OK → pos=%s, compass=%s",
                        i, c, coord(s), s.compass));
            }
            log(String.format("  [seg %d] 完成 (pos=%s, compass=%s)",
                    segIdx, coord(s), s.compass));
        }
        return true;
    }

    // =====================================================================
    // Stroker / 工具
    // =====================================================================

    private static final class Stroker {
        final HexDir startDir;
        final List<HexAngle> angles = new ArrayList<>();
        final Set<Long> lines = new HashSet<>();
        HexDir compass;
        int q = 0, r = 0;

        Stroker(HexDir startDir) {
            this.startDir = startDir;
            this.compass = startDir;
        }

        boolean canAppend(HexDir newDir) {
            if (newDir == compass.rotatedBy(HexAngle.BACK)) return false;
            int d = newDir.ordinal();
            int nq = q + DIR_DELTAS[d][0];
            int nr = r + DIR_DELTAS[d][1];
            int rev = newDir.rotatedBy(HexAngle.BACK).ordinal();
            return !lines.contains(edgeKey(q, r, d))
                    && !lines.contains(edgeKey(nq, nr, rev));
        }

        void append(HexDir newDir) {
            HexAngle angle = newDir.angleFrom(compass);
            int d = newDir.ordinal();
            int nq = q + DIR_DELTAS[d][0];
            int nr = r + DIR_DELTAS[d][1];
            int rev = newDir.rotatedBy(HexAngle.BACK).ordinal();
            lines.add(edgeKey(q, r, d));
            lines.add(edgeKey(nq, nr, rev));
            angles.add(angle);
            q = nq; r = nr; compass = newDir;
        }
    }

    private static long edgeKey(int q, int r, int dirOrdinal) {
        long h = 0xcbf29ce484222325L;
        h = (h ^ q) * 0x100000001b3L;
        h = (h ^ r) * 0x100000001b3L;
        h = (h ^ dirOrdinal) * 0x100000001b3L;
        return h;
    }

    private static HexDir pickMostSpacious(Stroker state, List<HexDir> valid) {
        HexDir best = null;
        int bestScore = -1;
        int ties = 0;
        final int q0 = state.q, r0 = state.r;
        for (HexDir d : valid) {
            int dOrd = d.ordinal();
            int nq = q0 + DIR_DELTAS[dOrd][0];
            int nr = r0 + DIR_DELTAS[dOrd][1];
            int rev = d.rotatedBy(HexAngle.BACK).ordinal();
            long e1 = edgeKey(q0, r0, dOrd);
            long e2 = edgeKey(nq, nr, rev);
            state.lines.add(e1); state.lines.add(e2);

            HexDir newBack = d.rotatedBy(HexAngle.BACK);
            int score = 0;
            for (HexDir d2 : ALL_DIRS) {
                if (d2 == newBack) continue;
                int d2Ord = d2.ordinal();
                int nnq = nq + DIR_DELTAS[d2Ord][0];
                int nnr = nr + DIR_DELTAS[d2Ord][1];
                int rev2 = d2.rotatedBy(HexAngle.BACK).ordinal();
                if (!state.lines.contains(edgeKey(nq, nr, d2Ord))
                        && !state.lines.contains(edgeKey(nnq, nnr, rev2))) {
                    score++;
                }
            }
            state.lines.remove(e1); state.lines.remove(e2);
            if (score > bestScore) { bestScore = score; best = d; ties = 1; }
            else if (score == bestScore) { ties++; if (RNG.nextInt(ties) == 0) best = d; }
        }
        return best;
    }

    private static String hashSuffix(String spellName, int len) {
        long h = mixSeed(spellName);
        char[] turns = {'w', 'e', 'q', 'd', 'a'};
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(turns[Math.floorMod(h, 5)]);
            h = h / 5 ^ 0x9E3779B97F4A7C15L;
        }
        return sb.toString();
    }

    private static long mixSeed(String s) {
        long seed = 0x9E3779B97F4A7C15L;
        for (int i = 0; i < s.length(); i++) {
            seed = seed * 31L + s.charAt(i);
            seed ^= seed >>> 27;
            seed *= 0x94D049BB133111EBL;
        }
        return seed;
    }

    private static String coord(Stroker s) { return "(" + s.q + "," + s.r + ")"; }

    private static String dirShort(List<HexDir> dirs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < dirs.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(dirs.get(i).name().charAt(0));
        }
        return sb.append("]").toString();
    }

    private static void log(String s) { HexEncrypt.LOGGER.info(s); }
}