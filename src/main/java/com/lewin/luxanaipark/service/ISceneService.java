package com.lewin.luxanaipark.service;

import com.lewin.commons.entity.LewinResult;
import com.lewin.luxanaipark.entity.Scene;
import com.lewin.luxanaipark.entity.TrafficParams;
import com.lewin.luxanaipark.entity.TrafficVO;

import java.util.List;

/**
 * @author Jun
 * @since 1.0.0
 */
public interface ISceneService {

    /**
     * 获取指定 scene traffic
     *
     * @param name scene name
     */
    LewinResult<List<TrafficVO>> traffic(String name);

    LewinResult<List<Scene>> all();

    LewinResult<Void> clean(TrafficParams params);
}
