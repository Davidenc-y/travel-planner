package com.travel.common.exception;

/**
 * 景点未找到异常
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
public class AttractionNotFoundException extends TravelBusinessException {

    public AttractionNotFoundException(Long id) {
        super(40401, "景点不存在: " + id);
    }

    public AttractionNotFoundException(String name) {
        super(40402, "景点不存在: " + name);
    }
}
