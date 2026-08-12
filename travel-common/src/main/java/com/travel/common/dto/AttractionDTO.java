package com.travel.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 景点数据传输对象
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttractionDTO {

    private Long id;
    private String name;
    private String city;
    private String type;
    private String description;
    private String address;
    private String openHours;
    private BigDecimal ticketPrice;
    private Boolean freeEntry;
    private BigDecimal rating;
    private List<String> tags;
    private String recommendedDuration;
    private String imageUrl;
}
