package com.lewin.luxanaipark.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 客流量
 *
 * @author Jun
 * @since 1.0.0
 */
@Data
@Accessors(chain = true)
public class Traffic {

    private Integer in;

    private Integer out;

    public void clear(){
        this.in = 0;
        this.out = 0;
    }
}
