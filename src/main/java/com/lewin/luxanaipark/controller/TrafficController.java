package com.lewin.luxanaipark.controller;

import com.lewin.commons.entity.LewinResult;
import com.lewin.luxanaipark.entity.Scene;
import com.lewin.luxanaipark.entity.TrafficParams;
import com.lewin.luxanaipark.entity.TrafficVO;
import com.lewin.luxanaipark.service.ISceneService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.PostConstruct;
import java.util.List;

/**
 * 流量接口
 *
 * @author Jun
 * @since 1.0.0
 */
@RestController
@CrossOrigin
@RequestMapping("/scene")
public class TrafficController {

    private final ISceneService sceneService;

    public TrafficController(ISceneService sceneService) {
        this.sceneService = sceneService;
    }

    @GetMapping("/all")
    public LewinResult<List<Scene>> all(){
        return sceneService.all();
    }

    @GetMapping("/traffic/{name}")
    public LewinResult<List<TrafficVO>> fetch(@PathVariable String name){
        return sceneService.traffic(name);
    }

    @PostMapping("/traffic-clean")
    public LewinResult<Void> clean(@RequestBody TrafficParams params){
        return sceneService.clean(params);
    }
}
