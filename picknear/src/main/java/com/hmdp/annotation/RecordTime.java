package com.hmdp.annotation;

import java.lang.annotation.*;

/**
 * 方法耗时记录注解 — 标注需要统计执行时间的方法
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RecordTime {

}
