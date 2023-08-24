package com.lewin.luxanaipark.entity;

import lombok.Data;

import java.util.List;

/**
 * @author Jun
 * @since 1.0.0
 */
@Data
public class Scene {

    private String name;

    private Integer capacity;

    /** 拥挤程度低 */
    private Integer low;

    /** 拥挤程度中等 */
    private Integer medium;

    /** 拥挤程度高 */
    private Integer high;

    private List<CameraInfo> cameraInfoList;
}
