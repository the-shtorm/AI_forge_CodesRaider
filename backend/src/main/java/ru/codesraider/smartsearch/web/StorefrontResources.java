package ru.codesraider.smartsearch.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

@Configuration
public class StorefrontResources implements WebMvcConfigurer {
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String path, Resource location) throws IOException {
                        String directory = path.isEmpty() || path.endsWith("/") ? path : path + "/";
                        Resource index = super.getResource(directory + "index.html", location);
                        if (index != null) {
                            return index;
                        }
                        return super.getResource(path, location);
                    }
                });
    }
}
