package com.yograj_learning_spring_boot.web_project.hello;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class SayHelloController {

    @RequestMapping("say-hello")
    @ResponseBody
    public String sayHello(){
        return "Hello! How are you? We Hope that you are fine!!!";
    }

    @RequestMapping("namaste")
    @ResponseBody
    public String namastey(){
        return "Hello! How are you? We Hope that you are fine!!!yeah";
    }

    @RequestMapping("say-hello-jsp")
    public String sayHelloJsp(){
        return "sayHello";
    }

}



