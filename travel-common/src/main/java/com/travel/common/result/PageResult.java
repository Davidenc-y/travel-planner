package com.travel.common.result;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 分页响应封装
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Data
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 数据列表 */
    private List<T> list;

    /** 总记录数 */
    private long total;

    /** 当前页码 */
    private int page;

    /** 每页大小 */
    private int size;

    /** 总页数 */
    private int totalPages;

    /**
     * 构造分页结果
     */
    public static <T> PageResult<T> of(List<T> list, long total, int page, int size) {
        PageResult<T> r = new PageResult<>();
        r.list = list;
        r.total = total;
        r.page = page;
        r.size = size;
        r.totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 0;
        return r;
    }

    /**
     * 空结果
     */
    public static <T> PageResult<T> empty(int page, int size) {
        return of(List.of(), 0, page, size);
    }
}
