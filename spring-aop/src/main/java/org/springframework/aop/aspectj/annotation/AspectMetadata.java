/*
 * Copyright 2002-2022 the original author or authors.
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

package org.springframework.aop.aspectj.annotation;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;

import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.AjType;
import org.aspectj.lang.reflect.AjTypeSystem;
import org.aspectj.lang.reflect.PerClauseKind;

import org.springframework.aop.Pointcut;
import org.springframework.aop.aspectj.AspectJExpressionPointcut;
import org.springframework.aop.aspectj.TypePatternClassFilter;
import org.springframework.aop.framework.AopConfigException;
import org.springframework.aop.support.ComposablePointcut;

/**
 * Metadata for an AspectJ aspect class, with an additional Spring AOP pointcut
 * for the per clause.
 *
 * <p>Uses AspectJ 5 AJType reflection API, enabling us to work with different
 * AspectJ instantiation models such as "singleton", "pertarget" and "perthis".
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @since 2.0
 * @see org.springframework.aop.aspectj.AspectJExpressionPointcut
 */
@SuppressWarnings("serial")
// TODO 切面元数据信息
public class AspectMetadata implements Serializable {

	/**
	 * The name of this aspect as defined to Spring (the bean name) -
	 * allows us to determine if two pieces of advice come from the
	 * same aspect and hence their relative precedence.
	 */
	// TODO 被@Aspect注解的切面的Class名
	private final String aspectName;

	/**
	 * The aspect class, stored separately for re-resolution of the
	 * corresponding AjType on deserialization.
	 */
	// TODO 被@Aspect注解的切面的Class
	private final Class<?> aspectClass;

	/**
	 * AspectJ reflection information.
	 * <p>Re-resolved on deserialization since it isn't serializable itself.
	 */
	// TODO 包装了被@Aspect所注解的切面, 其内部封装了关于这个切面的一些数据, 方法(位于org.aspectj下)
	private transient AjType<?> ajType;

	/**
	 * Spring AOP pointcut corresponding to the per clause of the
	 * aspect. Will be the Pointcut.TRUE canonical instance in the
	 * case of a singleton, otherwise an AspectJExpressionPointcut.
	 */
	// TODO 解析切入点表达式用的, 但是真正的解析工作为委托给'org.aspectj.weaver.tools.PointcutExpression'来解析的
	//  若是单例: 则是Pointcut.TRUE, 否则为AspectJExpressionPointcut
	private final Pointcut perClausePointcut;


	/**
	 * Create a new AspectMetadata instance for the given aspect class.
	 * @param aspectClass the aspect class
	 * @param aspectName the name of the aspect
	 */
	public AspectMetadata(Class<?> aspectClass, String aspectName) {
		this.aspectName = aspectName;

		Class<?> currClass = aspectClass;
		AjType<?> ajType = null;
		while (currClass != Object.class) {
			// TODO 把类包装成一个AspectJ特有的AjType
			AjType<?> ajTypeToCheck = AjTypeSystem.getAjType(currClass);
			if (ajTypeToCheck.isAspect()) {
				// TODO 判断一下要处理的类是否为被@Aspect注解过切面. 如果是, 得到类型后直接跳出
				ajType = ajTypeToCheck;
				break;
			}
			// TODO 如果不是, 深入其父类进行查找, 直到Object为止
			currClass = currClass.getSuperclass();
		}
		if (ajType == null) {
			// TODO 经过上面步骤后没有找到标有@Aspect的切面时, 抛出异常
			throw new IllegalArgumentException("Class '" + aspectClass.getName() + "' is not an @AspectJ aspect");
		}
		if (ajType.getDeclarePrecedence().length > 0) {
			// TODO Spring AOP也不支持优先级的声明
			throw new IllegalArgumentException("DeclarePrecedence not presently supported in Spring AOP");
		}
		// TODO 这里把元数据的aspectClass属性设置成被@Aspect注解的类的Class
		this.aspectClass = ajType.getJavaClass();
		this.ajType = ajType;
		// TODO 下面就是对切面的实例类型进行处理了, 目前只支持下面这四种类型
		switch (this.ajType.getPerClause().getKind()) {
			case SINGLETON -> {
				// TODO singleton表示切面只有一个实例, 所以其切点表达式perClausePointcut设置为TruePointcut.INSTANCE
				this.perClausePointcut = Pointcut.TRUE;
			}
			case PERTARGET, PERTHIS -> {
				// TODO pertarget表示每个切点表达式匹配的连接点所对应的目标对象都会创建一个新的切面实例, 比如:
				//  pertarget: @Aspect("pertarget(切点表达式)"), 这里的切点表达式不能是接口
				//  perthis表示每个切点表达式匹配的连接点所对应的aop对象(代理对象)都会创建一个新的切面实例, 比如
				//  @Aspect("perthis(切点表达式)"), 这里的切点表达式可以是接口
				//  这两种情况都需要把切面的Scope定义为prototype
				AspectJExpressionPointcut ajexp = new AspectJExpressionPointcut();
				ajexp.setLocation(aspectClass.getName());
				// TODO 从取出@Aspect注解使用的切点表达式, 然后设置给切点
				ajexp.setExpression(findPerClause(aspectClass));
				ajexp.setPointcutDeclarationScope(aspectClass);
				this.perClausePointcut = ajexp;
			}
			case PERTYPEWITHIN -> {
				// Works with a type pattern
				// TODO 组成的、合成得切点表达式
				this.perClausePointcut = new ComposablePointcut(new TypePatternClassFilter(findPerClause(aspectClass)));
			}
			// TODO Aspect的其余实例类型, 目前Spring AOP暂不支持
			default -> throw new AopConfigException(
					"PerClause " + ajType.getPerClause().getKind() + " not supported by Spring AOP for " + aspectClass);
		}
	}

	/**
	 * Extract contents from String of form {@code pertarget(contents)}.
	 */
	private String findPerClause(Class<?> aspectClass) {
		String str = aspectClass.getAnnotation(Aspect.class).value();
		int beginIndex = str.indexOf('(') + 1;
		int endIndex = str.length() - 1;
		// TODO 取出@Aspect注解中的切点表达式, 比如: @Aspect(pertarget(切点表达式)), 执行后得到的会是pertarget(切点表达式)
		return str.substring(beginIndex, endIndex);
	}


	/**
	 * Return AspectJ reflection information.
	 */
	public AjType<?> getAjType() {
		return this.ajType;
	}

	/**
	 * Return the aspect class.
	 */
	public Class<?> getAspectClass() {
		return this.aspectClass;
	}

	/**
	 * Return the aspect name.
	 */
	public String getAspectName() {
		return this.aspectName;
	}

	/**
	 * Return a Spring pointcut expression for a singleton aspect.
	 * (e.g. {@code Pointcut.TRUE} if it's a singleton).
	 */
	public Pointcut getPerClausePointcut() {
		return this.perClausePointcut;
	}

	/**
	 * Return whether the aspect is defined as "perthis" or "pertarget".
	 */
	public boolean isPerThisOrPerTarget() {
		PerClauseKind kind = getAjType().getPerClause().getKind();
		return (kind == PerClauseKind.PERTARGET || kind == PerClauseKind.PERTHIS);
	}

	/**
	 * Return whether the aspect is defined as "pertypewithin".
	 */
	public boolean isPerTypeWithin() {
		PerClauseKind kind = getAjType().getPerClause().getKind();
		return (kind == PerClauseKind.PERTYPEWITHIN);
	}

	/**
	 * Return whether the aspect needs to be lazily instantiated.
	 */
	public boolean isLazilyInstantiated() {
		return (isPerThisOrPerTarget() || isPerTypeWithin());
	}


	private void readObject(ObjectInputStream inputStream) throws IOException, ClassNotFoundException {
		inputStream.defaultReadObject();
		this.ajType = AjTypeSystem.getAjType(this.aspectClass);
	}

}
