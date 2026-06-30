package com.dataweave.master.application.lineage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 列级 A×B 交叉校验（承 spec FR-006，契约 C5）。
 *
 * <p>Agent 在 {@code .task.yaml} 可选择性声明列级 I/O，与 Calcite 解析结果比对：
 * <ul>
 *   <li>声明与解析<b>一致</b> → {@link Confidence#CONFIRMED}；</li>
 *   <li><b>仅解析</b>有 → 保留解析可信度（CONFIRMED/UNVERIFIED）；</li>
 *   <li><b>仅声明</b>有（解析未触及该目标列）→ {@link Confidence#DECLARED}（声明兜底）；</li>
 *   <li>同一目标列上声明与解析的<b>源不同</b> → {@link Confidence#CONFLICT}，<b>不静默丢弃</b>。</li>
 * </ul>
 */
public final class ColumnLineageCrossCheck {

    private ColumnLineageCrossCheck() {
    }

    public static List<ColumnEdge> crossValidate(List<ColumnEdge> parsed, List<ColumnEdge> declared) {
        Map<String, Set<String>> declSrcByDst = srcByDst(declared);
        Map<String, Set<String>> parsedSrcByDst = srcByDst(parsed);

        LinkedHashMap<String, ColumnEdge> out = new LinkedHashMap<>();

        // 1. 解析边：按是否被声明覆盖判定可信度
        for (ColumnEdge p : parsed) {
            String dk = dstKey(p);
            String sk = srcKey(p);
            Set<String> decl = declSrcByDst.get(dk);
            Confidence c;
            if (decl == null || decl.isEmpty()) {
                c = p.confidence();                 // 仅解析
            } else if (decl.contains(sk)) {
                c = Confidence.CONFIRMED;            // 一致
            } else {
                c = Confidence.CONFLICT;            // 同一 dst，解析源不在声明里
            }
            out.put(dk + "|" + sk, withConfidence(p, c));
        }

        // 2. 仅声明边（解析里没有这条具体 src→dst）
        for (ColumnEdge d : declared) {
            String dk = dstKey(d);
            String sk = srcKey(d);
            String key = dk + "|" + sk;
            if (out.containsKey(key)) {
                continue;                            // 已由解析侧记为一致
            }
            Set<String> par = parsedSrcByDst.get(dk);
            Confidence c = (par == null || par.isEmpty())
                    ? Confidence.DECLARED             // 仅声明（Agent 源，推导未印证）
                    : Confidence.CONFLICT;           // 解析对该 dst 给了别的源 → 声明边冲突
            out.put(key, withConfidence(d, c));
        }

        return new ArrayList<>(out.values());
    }

    private static Map<String, Set<String>> srcByDst(List<ColumnEdge> edges) {
        Map<String, Set<String>> m = new LinkedHashMap<>();
        for (ColumnEdge e : edges) {
            m.computeIfAbsent(dstKey(e), k -> new LinkedHashSet<>()).add(srcKey(e));
        }
        return m;
    }

    private static String dstKey(ColumnEdge e) {
        return NameNormalizer.fold(e.dstTable().qualifiedName()) + "." + NameNormalizer.fold(e.dstCol());
    }

    private static String srcKey(ColumnEdge e) {
        return NameNormalizer.fold(e.srcTable().qualifiedName()) + "." + NameNormalizer.fold(e.srcCol());
    }

    private static ColumnEdge withConfidence(ColumnEdge e, Confidence c) {
        return new ColumnEdge(e.srcTable(), e.srcCol(), e.dstTable(), e.dstCol(), e.transform(), c);
    }
}
