package com.travel.crawl.util;

import com.travel.crawl.config.CrawlProperties;
import com.travel.crawl.model.AttractionRaw;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 归一化去重（F104 2.9）：去空格/全半角/括号统一 + 别名表，生成去重键 normalize(name):city。
 */
@Component
public final class Normalizer {

    private final Map<String, String> aliasMap;

    public Normalizer(CrawlProperties props) {
        this.aliasMap = props.getImportCfg().getAliasMap();
    }

    public String dedupKey(AttractionRaw item) {
        return normalize(item.name()) + ":" + (item.city() == null ? "" : item.city().trim());
    }

    public String normalize(String name) {
        if (name == null) {
            return "";
        }
        String t = name.trim();
        if (aliasMap != null && aliasMap.containsKey(t)) {
            t = aliasMap.get(t);
        }
        // 全角→半角
        StringBuilder sb = new StringBuilder(t.length());
        for (char c : t.toCharArray()) {
            if (c >= 0xFF01 && c <= 0xFF5E) {
                sb.append((char) (c - 0xFEE0));
            } else if (c == 0x3000) {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        // 括号统一（全角→半角）后整体移除：含括号与否的写法归并为同一去重键
        t = sb.toString().replace('（', '(').replace('）', ')')
                .replace("(", "").replace(")", "");
        // 去连续空格
        t = t.replaceAll("\\s+", "").trim();
        return t;
    }
}
