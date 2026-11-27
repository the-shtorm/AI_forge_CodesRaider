package ru.codesraider.smartsearch.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class StorefrontResources implements WebMvcConfigurer {
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        for (String path : new String[]{"/catalog/", "/search/", "/vendors/", "/basket/", "/cyberprotect/",
                "/microsoft/ep2-06605-473582/", "/pantum/p2500w-363882/"}) {
            registry.addViewController(path).setViewName("forward:/index.html");
        }
    }
}
