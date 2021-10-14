package cn.jihongyun.demo.service.imp;

import cn.jihongyun.demo.service.TestService;
import cn.jihongyun.framework.core.annotation.Service;

@Service
public class TestServiceImp implements TestService {
    @Override
    public String hello(String name) {
        return "hello! "+name;
    }
}
