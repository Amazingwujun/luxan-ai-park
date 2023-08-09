package com.lewin.luxanaipark.config;

import com.lewin.luxanaipark.entity.Scene;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 业务配置
 *
 * @author Jun
 * @since 1.0.0
 */
@Data
@ConfigurationProperties(prefix = "lewin.luxan-ai-park")
public class BizProperties {

    private List<Scene> sceneList;

    private List<String> cronJobList;

    private String streamUrlPrefix;
}
