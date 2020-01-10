/*
 * Copyright 2002-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import org.springframework.lang.Nullable;

/**
 * {@link ParameterNameDiscoverer} implementation which uses JDK 8's reflection facilities
 * for introspecting parameter names (based on the "-parameters" compiler flag).
 *
 * @author Juergen Hoeller
 * @since 4.0
 * @see java.lang.reflect.Method#getParameters()
 * @see java.lang.reflect.Parameter#getName()
 */
// TODO 基于Java 8反射的标准参数名探测器, 需要为编译器使用'-parameters'参数
public class StandardReflectionParameterNameDiscoverer implements ParameterNameDiscoverer {

	@Override
	@Nullable
	public String[] getParameterNames(Method method) {
		return getParameterNames(method.getParameters());
	}

	@Override
	@Nullable
	public String[] getParameterNames(Constructor<?> ctor) {
		return getParameterNames(ctor.getParameters());
	}

	@Nullable
	// TODO 遍历方法的所有参数. 在指定了Java 8中javac命令的-parameters选项后参数后, 会把所有的形参都加到返回结果集中
	private String[] getParameterNames(Parameter[] parameters) {
		String[] parameterNames = new String[parameters.length];
		for (int i = 0; i < parameters.length; i++) {
			Parameter param = parameters[i];
			if (!param.isNamePresent()) {
				// TODO 如果没有包含方法的形参名信息, 则直接返回null. javac默认生成.class时是不包含形参名信息的. 只有为javac命令
				//  指定-parameters选项后, isNamePresent()才会返回true. 这都是Java 8新加的
				return null;
			}
			parameterNames[i] = param.getName();
		}
		return parameterNames;
	}

}
