package cn.jihongyun.framework.core;

import cn.jihongyun.framework.core.annotation.*;
import javafx.beans.binding.MapExpression;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RedCloudDispatcherServlet extends HttpServlet {

    Logger log = Logger.getLogger(this.getClass().getName());

    // 加载application.properties的信息
    private Properties properties = new Properties();
    // 保存扫描的所有的类名
    private List<String> classNames = new ArrayList<>();

    // ioc容器存储单例信息
    private Map<String,Object> ioc = new HashMap<>();

    // 路径匹配关系
    private List<Handler> handlerMapping = new ArrayList<>();

    // 初始化阶段
    @Override
    public void init(ServletConfig config) throws ServletException {
        // 加载配置配置
        doLoadConfig(config.getInitParameter("contextConfigLocation"));
        //扫描相关类信息
        doScanner(properties.getProperty("scanPackage"));
        //实例化保存至容器中
        doInstance();
        //完成依赖注入
        doAutowired();

        //初始化HandlerMapping
        initHandlerMapping();
        log.info("RedCloud Spring framework init finished.");
    }

    private void initHandlerMapping() {

        if (ioc.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : ioc.entrySet()) {
            Class<?> aClass = entry.getValue().getClass();
            if (!aClass.isAnnotationPresent(Controller.class)) {
                continue;
            }
            String baseUrl = "";
            if (aClass.isAnnotationPresent(RequestMapping.class)) {
                RequestMapping requestMapping = aClass.getAnnotation(RequestMapping.class);
                baseUrl = requestMapping.value();

                for (Method method : aClass.getDeclaredMethods()) {
                    if (!method.isAnnotationPresent(RequestMapping.class)) {
                        continue;
                    }
                    RequestMapping requestMapping1 = method.getAnnotation(RequestMapping.class);
                    String regex = ("/" + baseUrl + "/" + requestMapping1.value()).replaceAll("/+", "/");

                    Pattern pattern = Pattern.compile(regex);
                    this.handlerMapping.add(new Handler(pattern, method, entry.getValue()));
                }
            }
        }
    }

    private void doAutowired() {
        if (ioc.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : ioc.entrySet()) {
            Field[] fields = entry.getValue().getClass().getDeclaredFields();
            for (Field field : fields) {

                if (!field.isAnnotationPresent(Autowired.class)) {
                    continue;
                }

                String beanName = toLowerFirstCase(field.getType().getSimpleName());

                // 暴力访问，强制赋值
                field.setAccessible(true);
                try {
                    // 通过反射，动态给字段赋值
                    field.set(entry.getValue(),ioc.get(beanName));
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }


        }
    }

    private void doInstance() {
        if (classNames.isEmpty()) {
            return;
        }
        try {
            for (String className : classNames) {
                Class<?> aClass = Class.forName(className);
                if (aClass.isAnnotationPresent(Controller.class)) {
                    Object ins = aClass.newInstance();
                    String beanName = toLowerFirstCase(aClass.getSimpleName());
                    ioc.put(beanName, ins);
                } else if (aClass.isAnnotationPresent(Service.class)){
                    Object instance = aClass.newInstance();
                    Service annotation = aClass.getAnnotation(Service.class);
                    String beanName = annotation.value();
                    if (beanName.equals("")) {
                        beanName = toLowerFirstCase(aClass.getInterfaces()[0].getSimpleName());
                    }
                    ioc.put(beanName, instance);
                }
            }
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        } catch (InstantiationException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
    }

    private String toLowerFirstCase(String simpleName) {

        return simpleName.substring(0, 1).toLowerCase() + simpleName.substring(1);
    }

    private void doScanner(String scanPackage) {
        URL url = this.getClass().getClassLoader().getResource("/" + scanPackage.replaceAll("\\.", "/"));
        if (url == null) {
            log.info("scanPackage no exits.");
            return;
        }
        File parent = new File(url.getFile());

        for (File file : parent.listFiles()) {
            if (file.isDirectory()) {
                doScanner(scanPackage+"."+file.getName());
            } else {
                if (!file.getName().endsWith(".class")) {
                    continue;
                }
                String classname = scanPackage + "." + file.getName().replace(".class", "");
                classNames.add(classname);
            }
        }
    }

    private void doLoadConfig(String contextconfigLocation) {
        InputStream resourceAsStream = this.getClass().getClassLoader().getResourceAsStream(contextconfigLocation);

        try {
            properties.load(resourceAsStream);
        } catch (IOException e) {
            e.printStackTrace();
        }finally {
            if (null != resourceAsStream) {
                try {
                    resourceAsStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }


    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
      this.doPost(req,resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try {
            doDispatcher(req,resp);
        } catch (Exception e) {
            e.printStackTrace();
            resp.getWriter().write("500 exception,detail :"+ Arrays.toString(e.getStackTrace()));
        }
    }

    private void doDispatcher(HttpServletRequest req, HttpServletResponse resp) throws IOException, InvocationTargetException, IllegalAccessException {
        //根据请求获取对应处理的handler
        Handler handler = getHandler(req);
        if (handler == null) {
            resp.getWriter().write("404 Not Found!!!");
            return;
        }

        // 获取方法的参数类型并赋值
        Class<?>[] paramType = handler.getParamType();
        Object[] paramValues = new Object[paramType.length];
        Map<String, String[]> reqParameterMap = req.getParameterMap();
        for (Map.Entry<String, String[]> param : reqParameterMap.entrySet()) {
            String value = Arrays.toString(param.getValue()).replaceAll("\\[|\\]", "").replaceAll("\\s", ",");
            if (!handler.paramIndexMapping.containsKey(param.getKey())) {
                continue;
            }
            Integer index = handler.paramIndexMapping.get(param.getKey());
            paramValues[index] = convert(paramType[index], value);
        }
        if (handler.paramIndexMapping.containsKey(HttpServletRequest.class.getName())) {
            Integer reqIndex = handler.paramIndexMapping.get(HttpServletRequest.class.getName());
            paramValues[reqIndex] = req;
        }
        if (handler.paramIndexMapping.containsKey(HttpServletResponse.class.getName())) {
            Integer respIndex = handler.paramIndexMapping.get(HttpServletResponse.class.getName());
            paramValues[respIndex] = resp;
        }
        // 反射调用返回结果
        Object returnValue = handler.method.invoke(handler.controller, paramValues);
        if (returnValue == null || returnValue instanceof Void) {
            return;
        }
        resp.getWriter().write(returnValue.toString());
    }

    public Object convert(Class<?> type, String value) {

        if (Integer.class == type) {
            return Integer.valueOf(value);
        } else if (Double.class == type) {
            return Double.valueOf(value);
        }

        return value;
    }


    private Handler getHandler(HttpServletRequest req) {
        String uri = req.getRequestURI();
        String contextPath = req.getContextPath();
        uri = uri.replace(contextPath, "").replaceAll("/+", "/");
        for (Handler handler : this.handlerMapping) {
            Matcher matcher = handler.getPattern().matcher(uri);
            if (!matcher.matches()) {
                continue;
            }
            return handler;
        }
        return null;
    }


    public class Handler{
        public Map<String, Integer> paramIndexMapping;
        private Pattern pattern;
        private Method method;
        private Object controller;
        private Class<?> [] paramType;


        public Handler(Pattern pattern, Method method, Object controller) {
            this.pattern = pattern;
            this.method = method;
            this.controller = controller;
            this.paramType = method.getParameterTypes();
            this.paramIndexMapping = new HashMap<>();
            putParamIndexMapping(method);
        }

        private void putParamIndexMapping(Method method) {
            Annotation[][] annotations = method.getParameterAnnotations();
            for (int i = 0; i < annotations.length; i++) {
                for (Annotation annotation : annotations[i]) {
                    if (annotation instanceof RequestParam) {
                        String paramName = ((RequestParam) annotation).value();
                        if (!"".equals(paramName.trim())) {
                            paramIndexMapping.put(paramName, i);
                        } else {
                            String name = method.getParameters()[i].getName();
                            paramIndexMapping.put(name, i);
                        }
                    }
                }
            }
        }

        public Pattern getPattern() {
            return pattern;
        }

        public void setPattern(Pattern pattern) {
            this.pattern = pattern;
        }

        public Method getMethod() {
            return method;
        }

        public void setMethod(Method method) {
            this.method = method;
        }

        public Object getController() {
            return controller;
        }

        public void setController(Object controller) {
            this.controller = controller;
        }

        public Class<?>[] getParamType() {
            return paramType;
        }

        public void setParamType(Class<?>[] paramType) {
            this.paramType = paramType;
        }
    }

}
