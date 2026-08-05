package com.hmdp.agent.permission.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.hmdp.agent.permission.enums.DataAction;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiredDataPermission {
    /**
     * 数据操作动作
     * @return
     */
    DataAction action() default DataAction.READ;

    /**
     * 数据操作目标
     * @return
     */
    String resource() default "";
}
