package cn.jihongyun.demo.controller;

import cn.jihongyun.demo.service.TestService;
import cn.jihongyun.framework.core.annotation.Autowired;
import cn.jihongyun.framework.core.annotation.Controller;
import cn.jihongyun.framework.core.annotation.RequestMapping;
import cn.jihongyun.framework.core.annotation.RequestParam;

@Controller
@RequestMapping(value = "/test")
public class TestController {

    @Autowired
    private TestService testService;

    @RequestMapping(value = "/hello")
    public String hello(@RequestParam String name) {
        return testService.hello(name);
    }
}
