package com.lewin.luxanaipark.entity;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * @author Jun
 * @since 1.0.0
 */
@Data
@Accessors(chain = true)
public class TrafficParams {

    private String ip;

    private Integer port;
}
