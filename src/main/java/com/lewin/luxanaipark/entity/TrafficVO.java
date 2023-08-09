package com.lewin.luxanaipark.entity;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 流量对象
 *
 * @author Jun
 * @since 1.0.0
 */
@Data
@Accessors(chain = true)
public class TrafficVO {

    private Integer in;

    private Integer out;

    private String name;

    private String ip;

    private Integer port;

    private String location;

    private Boolean onlineFlag;

    private String streamUrl;
}
