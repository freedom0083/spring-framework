/*
 * Copyright 2002-present the original author or authors.
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

package org.springframework.beans.factory.support;

import java.beans.ConstructorProperties;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.apache.commons.logging.Log;
import org.jspecify.annotations.Nullable;

import org.springframework.beans.BeanMetadataElement;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.BeansException;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanReference;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.ConstructorArgumentValues.ValueHolder;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.core.CollectionFactory;
import org.springframework.core.KotlinDetector;
import org.springframework.core.MethodParameter;
import org.springframework.core.NamedThreadLocal;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.ResolvableType;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MethodInvoker;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * Delegate for resolving constructors and factory methods.
 *
 * <p>Performs constructor resolution through argument matching.
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Mark Fisher
 * @author Costin Leau
 * @author Sebastien Deleuze
 * @author Sam Brannen
 * @author Stephane Nicoll
 * @author Phillip Webb
 * @since 2.0
 * @see #autowireConstructor
 * @see #instantiateUsingFactoryMethod
 * @see #resolveConstructorOrFactoryMethod
 * @see AbstractAutowireCapableBeanFactory
 */
class ConstructorResolver {

	private static final Object[] EMPTY_ARGS = new Object[0];

	private static final NamedThreadLocal<InjectionPoint> currentInjectionPoint =
			new NamedThreadLocal<>("Current injection point");


	private final AbstractAutowireCapableBeanFactory beanFactory;

	private final Log logger;


	/**
	 * Create a new ConstructorResolver for the given factory and instantiation strategy.
	 * @param beanFactory the BeanFactory to work with
	 */
	public ConstructorResolver(AbstractAutowireCapableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
		this.logger = beanFactory.getLogger();
	}


	// BeanWrapper-based construction

	/**
	 * "autowire constructor" (with constructor arguments by type) behavior.
	 * Also applied if explicit constructor argument values are specified.
	 * @param beanName the name of the bean 要实例化的bean
	 * @param mbd the merged bean definition for the bean 要实例化的bean的mbd
	 * @param chosenCtors chosen candidate constructors (or {@code null} if none) 用于实例化bean的候选构造器, 或工厂方法
	 * @param explicitArgs argument values passed in programmatically via the getBean method,
	 * or {@code null} if none (-> use constructor argument values from bean definition) 实例化bean时, 指定的参数
	 * @return a BeanWrapper for the new instance
	 */
	@SuppressWarnings("NullAway") // Dataflow analysis limitation
	// TODO 整体和instantiateUsingFactoryMethod()类似, 只不过这里是用的构造器, 而非工厂方法
	public BeanWrapper autowireConstructor(String beanName, RootBeanDefinition mbd,
			Constructor<?> @Nullable [] chosenCtors, @Nullable Object @Nullable [] explicitArgs) {

		// TODO 创建一个用于返回的BeanWrapperImpl实例
		BeanWrapperImpl bw = new BeanWrapperImpl();
		// TODO 由容器来初始化, 初始化时会设置数据转换和属性编辑器
		this.beanFactory.initBeanWrapper(bw);
		// TODO 用于实例化bean的构造器
		Constructor<?> constructorToUse = null;
		// TODO 构造bean所需要的参数
		ArgumentsHolder argsHolderToUse = null;
		@Nullable Object[] argsToUse = null;

		if (explicitArgs != null) {
			// TODO 提供了用于实例化bean的参数时, 直接使用提供的参数
			argsToUse = explicitArgs;
		}
		else {
			// TODO 要解析参数
			Object[] argsToResolve = null;
			synchronized (mbd.constructorArgumentLock) {
				// TODO 从要创建的bean的mbd的缓存中拿出用于实例化bean的构造器
				constructorToUse = (Constructor<?>) mbd.resolvedConstructorOrFactoryMethod;
				if (constructorToUse != null && mbd.constructorArgumentsResolved) {
					// Found a cached constructor...
					// TODO 之前使用过该构造器创建bean实例时, 这里肯定已经缓存了解析好的用于实例化bean的构造器, 解析好的参数,
					//  或准备好的等解析参数等. 先看是否已经存在了解析过的构造器的参数
					argsToUse = mbd.resolvedConstructorArguments;
					if (argsToUse == null) {
						// TODO 如果参数还没解析好, 就拿出准备解析的用于构造器的参数
						argsToResolve = mbd.preparedConstructorArguments;
					}
				}
			}
			if (argsToResolve != null) {
				// TODO 解析参数
				argsToUse = resolvePreparedArguments(beanName, mbd, bw, constructorToUse, argsToResolve);
			}
		}

		if (constructorToUse == null || argsToUse == null) {
			// Take specified constructors, if any.
			// TODO 第一次使用构造器创建bean时会进到这里, 后面会对构造器进行筛选
			Constructor<?>[] candidates = chosenCtors;
			if (candidates == null) {
				// TODO 没有传需要的构造器时, 从bean对应的Class对象获取
				Class<?> beanClass = mbd.getBeanClass();
				try {
					// TODO 如果支持非public构造器, 会拿出bean所有的构造器. 否则只包含public构造器
					candidates = (mbd.isNonPublicAccessAllowed() ?
							beanClass.getDeclaredConstructors() : beanClass.getConstructors());
				}
				catch (Throwable ex) {
					throw new BeanCreationException(mbd.getResourceDescription(), beanName,
							"Resolution of declared constructors on bean Class [" + beanClass.getName() +
							"] from ClassLoader [" + beanClass.getClassLoader() + "] failed", ex);
				}
			}

			if (candidates.length == 1 && explicitArgs == null && !mbd.hasConstructorArgumentValues()) {
				// TODO 只有唯一的构造器, 并且没有实例化bean时需要的参数时(默认的无参构造器)
				Constructor<?> uniqueCandidate = candidates[0];
				if (uniqueCandidate.getParameterCount() == 0) {
					// TODO 如果是默认的无参构造器, 设置其缓存参数
					synchronized (mbd.constructorArgumentLock) {
						mbd.resolvedConstructorOrFactoryMethod = uniqueCandidate;
						mbd.constructorArgumentsResolved = true;
						mbd.resolvedConstructorArguments = EMPTY_ARGS;
					}
					// TODO 然后实例化bean, 设置到bw的实例上, 最后返回bw
					bw.setBeanInstance(instantiate(beanName, mbd, uniqueCandidate, EMPTY_ARGS));
					return bw;
				}
			}

			// Need to resolve the constructor.
			// TODO 下面就是有多个构造器, 或者构造器需要参数的情况了. 首选根据是否传入了用于实例化bean的构造器, 或者bean是否支持
			//  构造器注入来决定是否应用自动装配
			boolean autowiring = (chosenCtors != null ||
					mbd.getResolvedAutowireMode() == AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR);
			ConstructorArgumentValues resolvedValues = null;

			int minNrOfArgs;
			if (explicitArgs != null) {
				// TODO 指定了构造bean需要的参数时, 取得参数数量, 用于后面构造器的匹配选择
				minNrOfArgs = explicitArgs.length;
			}
			else {
				// TODO 没指定构造bean需要的参数时, 从配置文件的'constructor-arg'参数中拿出设置的参数
				ConstructorArgumentValues cargs = mbd.getConstructorArgumentValues();
				resolvedValues = new ConstructorArgumentValues();
				// TODO 解析构造器所需要的参数
				minNrOfArgs = resolveConstructorArguments(beanName, mbd, bw, cargs, resolvedValues);
			}

			AutowireUtils.sortConstructors(candidates);
			int minTypeDiffWeight = Integer.MAX_VALUE;
			Set<Constructor<?>> ambiguousConstructors = null;
			Deque<UnsatisfiedDependencyException> causes = null;

			for (Constructor<?> candidate : candidates) {
				// TODO 遍历所有的构造器, 拿出每个构造器的参数数量
				int parameterCount = candidate.getParameterCount();

				if (constructorToUse != null && argsToUse != null && argsToUse.length > parameterCount) {
					// Already found greedy constructor that can be satisfied ->
					// do not look any further, there are only less greedy constructors left.
					// TODO 只要找到了匹配的构造器, 直接退出匹配过程
					break;
				}
				if (parameterCount < minNrOfArgs) {
					// TODO 把指定的参数数量少的构造器也不需要
					continue;
				}

				ArgumentsHolder argsHolder;
				// TODO 取得构造器的类型数组
				Class<?>[] paramTypes = candidate.getParameterTypes();
				if (resolvedValues != null) {
					try {
						@Nullable String[] paramNames = null;
						if (resolvedValues.containsNamedArgument()) {
							// TODO 只有在没有指定实例化bean所需要的参数时才会进行参数解析, resolvedValues才会有值. 这时, 首选要
							//  评估一下候选构造器@ConstructorProperties注解的value内容. 如果没有, 返回的是null. 数量少于要求的
							//  参数数量时, 会抛出IllegalStateException异常
							paramNames = ConstructorPropertiesChecker.evaluate(candidate, parameterCount);
							if (paramNames == null) {
								// TODO 没有@ConstructorProperties注解时, 就要用参数名发现器来解析参数名了
								ParameterNameDiscoverer pnd = this.beanFactory.getParameterNameDiscoverer();
								if (pnd != null) {
									// TODO 容器里配置参数名发现器时, 用其构造器进行解析, 得到构造器所有的参数名
									paramNames = pnd.getParameterNames(candidate);
								}
							}
						}
						// TODO 用构造器的参数名, 参数类型来创建参数
						argsHolder = createArgumentArray(beanName, mbd, resolvedValues, bw, paramTypes, paramNames,
								getUserDeclaredConstructor(candidate), autowiring, candidates.length == 1);
					}
					catch (UnsatisfiedDependencyException ex) {
						if (logger.isTraceEnabled()) {
							logger.trace("Ignoring constructor [" + candidate + "] of bean '" + beanName + "': " + ex);
						}
						// Swallow and try next constructor.
						if (causes == null) {
							causes = new ArrayDeque<>(1);
						}
						causes.add(ex);
						continue;
					}
				}
				else {
					// Explicit arguments given -> arguments length must match exactly.
					// TODO 对于指定了实例化bean时需要的参数的情况
					if (parameterCount != explicitArgs.length) {
						// TODO 跳过参数数量与指定的参数数量不匹配的构造器
						continue;
					}
					// TODO 然后用指定的参数来构建实例化bean所需要的ArgumentsHolder参数
					argsHolder = new ArgumentsHolder(explicitArgs);
				}
				// TODO 根据要实例化的bean的mbd的构造函数是宽松模式还是严格模式来分别计算权重:
				//  1. 宽松模式: 构造器参数类型与解析后的构造器的参数的类型, 及解析后的构造器的参数的原生类型的最小权重值
				//  2. 严格模式: 构造器参数类型与解析后的构造器的参数的类型, 及解析后的构造器的参数的原生类型直接比较
				int typeDiffWeight = (mbd.isLenientConstructorResolution() ?
						argsHolder.getTypeDifferenceWeight(paramTypes) : argsHolder.getAssignabilityWeight(paramTypes));
				// Choose this constructor if it represents the closest match.
				// TODO 匹配最接近的构造器. 这里注意minTypeDiffWeight是在for循环外部定义, 一旦差异权重比最小差异权重小, 则
				//  更新相关属性, 包括: 将使用的构造器, argsHolderToUse, argsToUse, minTypeDiffWeight
				if (typeDiffWeight < minTypeDiffWeight) {
					// TODO 权重小于最小预期的构造器做为要用的构造器, 同时赋值参数, 构造函数所要用到的参数
					constructorToUse = candidate;
					argsHolderToUse = argsHolder;
					argsToUse = argsHolder.arguments;
					// TODO 更新最小预期值
					minTypeDiffWeight = typeDiffWeight;
					ambiguousConstructors = null;
				}
				else if (constructorToUse != null && typeDiffWeight == minTypeDiffWeight) {
					// TODO 当出现权重相同的情况, 就表示出现了有歧义的构造器, 这些有歧义的构造器会被加到歧义缓存中用于最终抛出异常
					if (ambiguousConstructors == null) {
						ambiguousConstructors = new LinkedHashSet<>();
						ambiguousConstructors.add(constructorToUse);
					}
					ambiguousConstructors.add(candidate);
				}
			}

			if (constructorToUse == null) {
				// TODO 所有的候选构造器全部解析后, 如果还没有得到实例化bean所需要的构造器时
				if (causes != null) {
					UnsatisfiedDependencyException ex = causes.removeLast();
					for (Exception cause : causes) {
						// TODO 有异常时, 将其放到容器中, 然后再抛出UnsatisfiedDependencyException异常
						this.beanFactory.onSuppressedException(cause);
					}
					throw ex;
				}
				// TODO 异常列表里没有记录任何异常时, 则抛出BeanCreationException
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Could not resolve matching constructor on bean class [" + mbd.getBeanClassName() + "] " +
						"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities. " +
						"You should also check the consistency of arguments when mixing indexed and named arguments, " +
						"especially in case of bean definition inheritance)");
			}
			else if (ambiguousConstructors != null && !mbd.isLenientConstructorResolution()) {
				// TODO 严格模式下出现有歧义的构造器时, 也抛出BeanCreationException异常
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Ambiguous constructor matches found on bean class [" + mbd.getBeanClassName() + "] " +
						"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities): " +
						ambiguousConstructors);
			}

			if (explicitArgs == null && argsHolderToUse != null) {
				// TODO 没有异常发生时, 没指定实例化bean所需要的参数, 但解析出了所需要的参数时, 把解析出来的参数, 以及实例化bean时
				//  用到的构造器放到要实例化的bean的mbd中
				argsHolderToUse.storeCache(mbd, constructorToUse);
			}
		}

		Assert.state(argsToUse != null, "Unresolved constructor arguments");
		// TODO 开始实例化, 然后把实例化好的bean放到BeanWrapperImpl中返回
		bw.setBeanInstance(instantiate(beanName, mbd, constructorToUse, argsToUse));
		return bw;
	}

	/**
	 *
	 * @param beanName 要实例化的bean
	 * @param mbd 要实例化的bean的mbd
	 * @param constructorToUse 用于实例化bean的构造器
	 * @param argsToUse 实例化bean时所需要的参数
	 * @return 实例化后的bean
	 */
	// TODO 正式开始实例化bean
	private Object instantiate(
			String beanName, RootBeanDefinition mbd, Constructor<?> constructorToUse, Object[] argsToUse) {

		try {
			// TODO 从容器中取得实例化策略
			InstantiationStrategy strategy = this.beanFactory.getInstantiationStrategy();
			// TODO 直接使用策略进行实例化
			return strategy.instantiate(mbd, beanName, this.beanFactory, constructorToUse, argsToUse);
		}
		catch (Throwable ex) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName, ex.getMessage(), ex);
		}
	}

	/**
	 * Resolve the factory method in the specified bean definition, if possible.
	 * {@link RootBeanDefinition#getResolvedFactoryMethod()} can be checked for the result.
	 * @param mbd the bean definition to check
	 */
	public void resolveFactoryMethodIfPossible(RootBeanDefinition mbd) {
		Class<?> factoryClass;
		boolean isStatic;
		if (mbd.getFactoryBeanName() != null) {
			// TODO 有工厂类的名字时, 表示为一个实例工厂. 取得实例工厂类的class类型
			factoryClass = this.beanFactory.getType(mbd.getFactoryBeanName());
			// TODO 有工厂类肯定是非静态工厂方法
			isStatic = false;
		}
		else {
			// TODO 没有工厂类时, 表示为一个静态工厂, 用mbd的类型即可
			factoryClass = mbd.getBeanClass();
			isStatic = true;
		}
		Assert.state(factoryClass != null, "Unresolvable factory class");
		// TODO 取得class, 这边是处理了一下可能存在的由CGLIB增强的类的情况
		factoryClass = ClassUtils.getUserClass(factoryClass);
		// TODO 获取工厂类的所有公有方法(如果mbd允许访问非公有方法, 则还会包括所有非公有方法)
		Method[] candidates = getCandidateMethods(factoryClass, mbd);
		Method uniqueCandidate = null;
		for (Method candidate : candidates) {
			if ((!isStatic || isStaticCandidate(candidate, factoryClass)) && mbd.isFactoryMethod(candidate)) {
				// TODO 迭代候选方法中的所有静态工厂方法
				if (uniqueCandidate == null) {
					// TODO 如果还没有唯一候选方法, 则将其设置为唯一候选方法
					uniqueCandidate = candidate;
				}
				else if (isParamMismatch(uniqueCandidate, candidate)) {
					// TODO 当内省方法与候选方法参数数量, 或者类型不相同时, 表示末命中, 清空内省方法并退出循环
					uniqueCandidate = null;
					break;
				}
			}
		}
		// TODO 设置mbd的内省的工厂方法
		mbd.factoryMethodToIntrospect = uniqueCandidate;
	}

	// TODO 判断参数是否不匹配
	private boolean isParamMismatch(Method uniqueCandidate, Method candidate) {
		int uniqueCandidateParameterCount = uniqueCandidate.getParameterCount();
		int candidateParameterCount = candidate.getParameterCount();
		// TODO 判断的标准是内省方法与候选方法参数数量是否不相同, 或者参数列表是否不相同
		return (uniqueCandidateParameterCount != candidateParameterCount ||
				!Arrays.equals(uniqueCandidate.getParameterTypes(), candidate.getParameterTypes()));
	}

	/**
	 * Retrieve all candidate methods for the given class, considering
	 * the {@link RootBeanDefinition#isNonPublicAccessAllowed()} flag.
	 * Called as the starting point for factory method determination.
	 *
	 * @param factoryClass 要取得方法的工厂类
	 * @param mbd 正在操作的bean的mbd
	 * @return
	 */
	// TODO 取得工厂类对象中所有的方法, 根据mbd是否允许访问非公有方法分为两处情况:
	//  1. 允许非公有方法: 通过ReflectionUtils工具类, 取得所有定义的方法
	//  2. 不允许非公有方法: 直接调用类对象取得所有公有方法
	private Method[] getCandidateMethods(Class<?> factoryClass, RootBeanDefinition mbd) {
		return (mbd.isNonPublicAccessAllowed() ?
				ReflectionUtils.getUniqueDeclaredMethods(factoryClass) : factoryClass.getMethods());
	}

	private boolean isStaticCandidate(Method method, Class<?> factoryClass) {
		return (Modifier.isStatic(method.getModifiers()) && method.getDeclaringClass() == factoryClass);
	}

	/**
	 * Instantiate the bean using a named factory method. The method may be static, if the
	 * bean definition parameter specifies a class, rather than a "factory-bean", or
	 * an instance variable on a factory object itself configured using Dependency Injection.
	 * <p>Implementation requires iterating over the static or instance methods with the
	 * name specified in the RootBeanDefinition (the method may be overloaded) and trying
	 * to match with the parameters. We don't have the types attached to constructor args,
	 * so trial and error is the only way to go here. The explicitArgs array may contain
	 * argument values passed in programmatically via the corresponding getBean method.
	 * @param beanName the name of the bean 要创建实例的bean名
	 * @param mbd the merged bean definition for the bean 要创建实例的bean的mbd
	 * @param explicitArgs argument values passed in programmatically via the getBean
	 * method, or {@code null} if none (-> use constructor argument values from bean definition) 创建bean时用户指定的参数值,
	 *                     只有构造方法和工厂方法可以用此参数来给取得的bean赋值, 同时bean的scope也必需是prototype类型. 如果传入了此值,
	 *                     可以通过这个参数直接确定用于初始化的构造函数
	 * @return a BeanWrapper for the new instance
	 */
	@SuppressWarnings("NullAway") // Dataflow analysis limitation
	// TODO 用工厂方法实例化对应的 bean. 工厂方法有两种类型:
	//  1. 静态工厂方法: 不需要直接实例化工厂类即可使用工厂方法, 类似于静态类:
	//     A. XML配置方式:
	//        <bean id="car" class="factory.StaticCarFactory" factory-method="getCar">
	//            <constructor-arg value="Audio" />
	//        </bean>
	//        a. factoryBeanName: null, 静态工厂方法没有工厂
	//        b. factoryBean: null, 静态工厂方法没有工厂类
	//        c. factoryClass: 'class' 属性, 指向的是静态工厂方法的全限定名, 即: 例子中的 'factory.StaticCarFactory'
	//        d. factoryMethod: 'factory-method' 属性, 指向静态工厂方法的名字, 即: 例子中的 'getCar'
	//        e. explicitArgs: 'constructor-arg' 标签所指定的, 用于调用工厂方法时使用的参数, 即: 例子中的 'Audio'
	//     B. 注解配置方式, 解析过程在 ConfigurationClassBeanDefinitionReader#loadBeanDefinitionsForBeanMethod(BeanMethod) 方法中:
	//        package factory
	//        @Configuration
	//        Class CarConfiguration {
	//            @Bean
	//            public static getCar() {
	//            }
	//        }
	//        a. factoryBeanName: null, 静态工厂方法没有工厂
	//        b. factoryBean: null, 静态工厂方法没有工厂类
	//        c. factoryClass: 配置类的全限定名, 即: 例子中的 'factory.CarConfiguration'
	//        d. factoryMethod: @Bean 所标注的static方法 mark
	//        e. explicitArgs: mark
	//  2. 实例工厂: 实例化后才能使用工厂方法, 类似于普通类, 实例工厂没有 'class' 属性:
	//     A. XML配置方式:
	//        <bean id="carFactory" class="xxx.CarFactory">
	//        <bean id="car" factory-bean="carFactory" factory-method="getCar">
	//            <constructor-arg value="BMW"></constructor-arg>
	//        </bean>
	//        a. factoryBeanName: 'factory-bean' 属性指定的实例工厂方法的名字, 调用工厂方法前需要实例化的类, 即: 例子中的 'carFactory'
	//        b. factoryBean: 'factory-bean' 属性所指定的bean实例, 即: 例子中的 '<bean id="carFactory" class="xxx.CarFactory">'
	//        c. factoryClass: 'factory-bean' 属性所指定的bean实例的Class对象, 即: 例子中的 'xxx.CarFactory'
	//        d. factoryMethod: 'factory-method' 属性: 指向实例工厂方法的名字, 即: 例子中的 'getCar'
	//        e. explicitArgs: 'constructor-arg' 标签所指定的, 用于调用工厂方法时使用的参数, 即: 例子中的 'Audio'
	//     B. 注解配置方式, 解析过程在 ConfigurationClassBeanDefinitionReader#loadBeanDefinitionsForBeanMethod(BeanMethod) 方法中:
	//        package factory
	//        @Configuration
	//        Class CarConfiguration {
	//            @Bean
	//            public static getCar() {
	//            }
	//        }
	//        a. factoryBeanName: 注解方式的工厂类为 @Bean 所在的类的名字, 即: 例子中的 'CarConfiguration'
	//        a. factoryBean: 注解方式的工厂类为 @Bean 所在的类, 即, 例子中的 'CarConfiguration'
	//        b. factoryClass: 工厂类的 Class 对象, 即, 例子中的 'CarConfiguration'
	//        c. factoryMethod: @Bean 所标注的方法 mark
	//        d. explicitArgs: mark
	public BeanWrapper instantiateUsingFactoryMethod(
			String beanName, RootBeanDefinition mbd, @Nullable Object @Nullable [] explicitArgs) {

		// TODO 创建一个用于返回的 BeanWrapperImpl 实例
		BeanWrapperImpl bw = new BeanWrapperImpl();
		// TODO 由容器来初始化, 初始化时会设置数据转换和属性编辑器
		this.beanFactory.initBeanWrapper(bw);
		// TODO BeanDefinition 使用的工厂类对象(实现了 FactoryBean 接口的对象)
		Object factoryBean;
		// TODO 工厂类
		Class<?> factoryClass;
		// TODO 是否为静态工厂方法
		boolean isStatic;
		// TODO 配置 bean 时会指定工厂类, 从要创建实例的 bean 的 mbd 中获取配置的工厂类的名字. 这边和 AbstractAutowireCapableBeanFactory 类似
		//  1. 静态工厂: 在配置文件中不需要指定工厂类, 所以不会有工厂类的名字.
		//  2. 实例工厂: 在 xml 配置文件中指定对应的工厂类, 如果是注解形式, 则 @Bean 为所在的类的类名. 必须先实例化该工厂类后才能使用其指定的工厂方法.
		String factoryBeanName = mbd.getFactoryBeanName();
		if (factoryBeanName != null) {
			// TODO 实例工厂在配置时需要指定一个工厂类, 所以才会有工厂类
			if (factoryBeanName.equals(beanName)) {
				// TODO 工厂类的名字不能和要实例化的 bean 同名(不能自己创建自己)
				throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
						"factory-bean reference points back to the same bean definition");
			}
			// TODO 实例工厂在使用前需要先进行实例化. 从容器中取出工厂类实例(如果没有实例化, 会进行实例化)
			factoryBean = this.beanFactory.getBean(factoryBeanName);
			if (mbd.isSingleton() && this.beanFactory.containsSingleton(beanName)) {
				// TODO 要创建实例的 bean 是单例, 并且已经存在于容器中时, 会报一个隐匿创建异常 mark
				throw new ImplicitlyAppearedSingletonException();
			}
			// TODO 注册 bean 的依赖关系. 当前操作的 bean 有一个保存所有依赖其的 bean 的列表. 这里会把依赖 bean 插进去. 同时还会更新依赖 bean
			//  本身自带的依赖了哪些 bean 的集合
			this.beanFactory.registerDependentBean(factoryBeanName, beanName);
			// TODO 取得工厂类的类型(xxx.CarFactory)
			factoryClass = factoryBean.getClass();
			isStatic = false;
		}
		else {
			// It's a static factory method on the bean class.
			// TODO 没有名字的就是静态工厂, 静态工厂的工厂方法可以直接使用不需要先实例化工厂类
			if (!mbd.hasBeanClass()) {
				// TODO 要通过静态工厂实例化的 bean 必须设置全限定名, 即 'class' 属性, 否则无法调用静态工厂方法. 因为, 没有指定 class 属性
				//  或者设置的不是 Class 类型时, 会抛出异常
				throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
						"bean definition declares neither a bean class nor a factory-bean reference");
			}
			// TODO 静态工厂没有工厂类
			factoryBean = null;
			// TODO 要通过静态工厂实例化的 bean 所配置的 'class' 属性就是工厂类的类型(factory.StaticCarFactory)
			factoryClass = mbd.getBeanClass();
			isStatic = true;
		}
		// TODO 用于创建实例的工厂方法
		Method factoryMethodToUse = null;
		// TODO 创建工厂方法所需要的参数
		ArgumentsHolder argsHolderToUse = null;
		// TODO 所有后续要用的参数
		@Nullable Object[] argsToUse = null;

		if (explicitArgs != null) {
			// TODO 如果指定了实例化 bean 所需要的参数时, 直接使用这些参数进行实例化
			argsToUse = explicitArgs;
		}
		else {
			// TODO 如果没指定实例化 bean 所需要的参数, 需要先对其进行解析
			@Nullable Object[] argsToResolve = null;
			// TODO 解析参数时需要进行同步
			synchronized (mbd.constructorArgumentLock) {
				// TODO 先看一下缓存里是否有用于实例化的 bean 的已经解析好的工厂方法(不是第一次使用此方法)
				factoryMethodToUse = (Method) mbd.resolvedConstructorOrFactoryMethod;
				if (factoryMethodToUse != null && mbd.constructorArgumentsResolved) {
					// Found a cached factory method...
					// TODO 如果缓存里有解析好的工厂方法, 并且其是使用的参数已经解析过了, 则表示并不是第一次使用此工厂方法.
					//  此时缓存中可能存在已经解析过的工厂方法的参数, 这个参数就可以当做实例化 bean 时所需要的参数
					argsToUse = mbd.resolvedConstructorArguments;
					if (argsToUse == null) {
						// TODO 如果缓存中没有解析好的工厂方法的参数, 则拿出准备好, 但还未解析的工厂方法的参数做为要解析的值
						argsToResolve = mbd.preparedConstructorArguments;
					}
				}
			}
			if (argsToResolve != null) {
				// TODO 如果有还未解析的工厂方法的参数, 表示其肯定有可用来实例化 bean 的工厂方法, 即: factoryMethodToUse 肯定不为null.
				//  所以这里就开始解析准备好的参数(这里会进行 @Value, @Autowire 自动注入, property 解析, 参数的 SpEL 表达式, 和值的处理)
				argsToUse = resolvePreparedArguments(beanName, mbd, bw, factoryMethodToUse, argsToResolve);
			}
		}

		if (factoryMethodToUse == null || argsToUse == null) {
			// Need to determine the factory method...
			// Try all methods with this name to see if they match the given arguments.
			// TODO 如果缓存中没有用于实例化bean所需要的解析好的工厂方法; 或者没有指定实例化bean所需要的参数时, 说明这是第一次使用
			//  工厂方法. 这时需要重头解析一次工厂方法. 首选, 取得工厂类. 这里会判断其是否为CGLIB生成的子类, 即名字是否包含'$$'.
			//  如果是, 返回的会是其超类(由CGLIB所代理的原始类)
			factoryClass = ClassUtils.getUserClass(factoryClass);

			List<Method> candidates = null;
			// TODO isFactoryMethodUnique属性默认为false. 在通过配置类进行配置时, @Bean可以标注同名方法. 当同名方法在相同配置类
			//  中时, 会通过ConfigurationClassBeanDefinitionReader#isOverriddenByExistingDefinition()方法进行方法重载.
			//  这时isFactoryMethodUnique就会被设置为true.
			if (mbd.isFactoryMethodUnique) {
				// TODO 如果要实例化的bean被重载过(同一个配置类中有被@Bean标注的同名方法)
				if (factoryMethodToUse == null) {
					// TODO 但是目前还找到没有用于实例化bean的工厂方法时(第一次使用此工厂方法时, 属性factoryMethodToUse会为null,
					//  原因是还没有进行解析), 用取得要实例化的bean的mbd中的内省工厂方法做为实例化bean的工厂方法
					factoryMethodToUse = mbd.getResolvedFactoryMethod();
				}
				if (factoryMethodToUse != null) {
					// TODO 如果解析到了工厂方法, 将其包装为只包含一个元素的List做为要检查的候选集合
					candidates = Collections.singletonList(factoryMethodToUse);
				}
			}
			if (candidates == null) {
				candidates = new ArrayList<>();
				// TODO 没有任何候选的工厂方法时, 就要自己找了. 先取得工厂类中所有的方法
				Method[] rawCandidates = getCandidateMethods(factoryClass, mbd);
				for (Method candidate : rawCandidates) {
					if ((!isStatic || isStaticCandidate(candidate, factoryClass)) && mbd.isFactoryMethod(candidate)) {
						// TODO 把其中的静态工厂方法加入到要检查的候选集合中
						candidates.add(candidate);
					}
				}
			}

			if (candidates.size() == 1 && explicitArgs == null && !mbd.hasConstructorArgumentValues()) {
				// TODO 候选集合只有唯一的工厂方法, 且正在创建的bean的构造函数没有参数, 且在getBean()并没有传入参数为取得的为bean赋值过时
				Method uniqueCandidate = candidates.get(0);
				if (uniqueCandidate.getParameterCount() == 0) {
					// TODO 无参工厂方法会被做为自省方法, 然后在加锁的情况下, 设置mbd的缓存
					mbd.factoryMethodToIntrospect = uniqueCandidate;
					synchronized (mbd.constructorArgumentLock) {
						mbd.resolvedConstructorOrFactoryMethod = uniqueCandidate;
						mbd.constructorArgumentsResolved = true;
						mbd.resolvedConstructorArguments = EMPTY_ARGS;
					}
					// TODO 实例化bean后设置为BeanWrapperImpl的实例, 然后返回
					bw.setBeanInstance(instantiate(beanName, mbd, factoryBean, uniqueCandidate, EMPTY_ARGS));
					return bw;
				}
			}

			if (candidates.size() > 1) {  // explicitly skip immutable singletonList
				// TODO 当有多个工厂方法时, 对工厂方法按照参数数量进行排序
				candidates.sort(AutowireUtils.EXECUTABLE_COMPARATOR);
			}

			ConstructorArgumentValues resolvedValues = null;
			// TODO 看mbd是否支持自动装配, 对于构造函数来说, mbd的自动装配模式为AUTOWIRE_AUTODETECT, 且没有无参构造函数时才允许自动装配
			//  工厂方法构造，这里autowiring=false
			boolean autowiring = (mbd.getResolvedAutowireMode() == AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR);
			int minTypeDiffWeight = Integer.MAX_VALUE;
			// TODO 会引起歧义的方法列表
			Set<Method> ambiguousFactoryMethods = null;

			int minNrOfArgs;
			if (explicitArgs != null) {
				minNrOfArgs = explicitArgs.length;
			}
			else {
				// We don't have arguments passed in programmatically, so we need to resolve the
				// arguments specified in the constructor arguments held in the bean definition.
				if (mbd.hasConstructorArgumentValues()) {
					// TODO 提取配置文件中定义的创建bean时需要用的参数(constructor-arg)
					ConstructorArgumentValues cargs = mbd.getConstructorArgumentValues();
					resolvedValues = new ConstructorArgumentValues();
					// TODO 解析这些参数, resolvedValues中会得到解析结果. 也会返回解析参数的数量
					minNrOfArgs = resolveConstructorArguments(beanName, mbd, bw, cargs, resolvedValues);
				}
				else {
					minNrOfArgs = 0;
				}
			}

			Deque<UnsatisfiedDependencyException> causes = null;

			for (Method candidate : candidates) {
				int parameterCount = candidate.getParameterCount();

				if (parameterCount >= minNrOfArgs) {
					// TODO 要找最接近的方法, 参数数量比工厂方法少的肯定不是, 所以这里只看参数数量 >= 工厂方法参数数量的方法
					//  这个是用于保存参数的holder
					ArgumentsHolder argsHolder;

					// TODO 拿出每个方法参数的类型数组
					Class<?>[] paramTypes = candidate.getParameterTypes();
					if (explicitArgs != null) {
						// Explicit arguments given -> arguments length must match exactly.
						if (paramTypes.length != explicitArgs.length) {
							// TODO 参数数量与赋给bean的参数数量不匹配的也不要
							continue;
						}
						argsHolder = new ArgumentsHolder(explicitArgs);
					}
					else {
						// Resolved constructor arguments: type conversion and/or autowiring necessary.
						try {
							@Nullable String[] paramNames = null;
							if (resolvedValues != null && resolvedValues.containsNamedArgument()) {
								// TODO 从容器中拿出参数名发现器, 默认是DefaultParameterNameDiscoverer. 在getTypeForFactoryMethod()
								//  方法中也有同样的逻辑
								ParameterNameDiscoverer pnd = this.beanFactory.getParameterNameDiscoverer();
								if (pnd != null) {
									// TODO 用探测器取得方法参数的名字, 有好多实现, 这个得慢慢看, 最后得到的就是参数名
									paramNames = pnd.getParameterNames(candidate);
								}
							}
							// TODO 用工厂方法的参数名, 参数类型来创建参数
							argsHolder = createArgumentArray(beanName, mbd, resolvedValues, bw,
									paramTypes, paramNames, candidate, autowiring, candidates.size() == 1);
						}
						catch (UnsatisfiedDependencyException ex) {
							// TODO 忽略无法创建的依赖
							if (logger.isTraceEnabled()) {
								logger.trace("Ignoring factory method [" + candidate + "] of bean '" + beanName + "': " + ex);
							}
							// Swallow and try next overloaded factory method.
							if (causes == null) {
								causes = new ArrayDeque<>(1);
							}
							causes.add(ex);
							continue;
						}
					}
					// TODO 根据要实例化的bean的mbd的构造函数是宽松模式还是严格模式来分别计算权重:
					//  1. 宽松模式: 工厂方法参数类型与解析后的构造函数的参数的类型, 及解析后的构造函数的参数的原生类型的最小权重值
					//  2. 严格模式: 工厂方法参数类型与解析后的构造函数的参数的类型, 及解析后的构造函数的参数的原生类型直接比较
					int typeDiffWeight = (mbd.isLenientConstructorResolution() ?
							argsHolder.getTypeDifferenceWeight(paramTypes) : argsHolder.getAssignabilityWeight(paramTypes));
					// Choose this factory method if it represents the closest match.
					// TODO 匹配最接近的工厂方法. 这里注意minTypeDiffWeight是在for循环外部定义, 一旦差异权重比最小差异权重小, 则
					//  更新相关属性, 包括: 将使用的工厂方法, argsHolderToUse, argsToUse, minTypeDiffWeight
					if (typeDiffWeight < minTypeDiffWeight) {
						// TODO 权重小于最小预期的方法做为要用的工厂方法, 同时赋值参数, 构造函数参数
						factoryMethodToUse = candidate;
						argsHolderToUse = argsHolder;
						argsToUse = argsHolder.arguments;
						// TODO 更新最小预期值
						minTypeDiffWeight = typeDiffWeight;
						ambiguousFactoryMethods = null;
					}
					// Find out about ambiguity: In case of the same type difference weight
					// for methods with the same number of parameters, collect such candidates
					// and eventually raise an ambiguity exception.
					// However, only perform that check in non-lenient constructor resolution mode,
					// and explicitly ignore overridden methods (with the same parameter signature).
					else if (factoryMethodToUse != null && typeDiffWeight == minTypeDiffWeight &&
							!mbd.isLenientConstructorResolution() &&
							paramTypes.length == factoryMethodToUse.getParameterCount() &&
							!Arrays.equals(paramTypes, factoryMethodToUse.getParameterTypes())) {
						// TODO 对于严格模式来说, 如果出现相同权重, 参数数量, 但参数类型不同的工厂方法时, 表示出现歧义方法, 这些
						//  歧义方法都会被加到歧义缓存中用于最终抛出异常
						if (ambiguousFactoryMethods == null) {
							ambiguousFactoryMethods = new LinkedHashSet<>();
							ambiguousFactoryMethods.add(factoryMethodToUse);
						}
						ambiguousFactoryMethods.add(candidate);
					}
				}
			}
			// TODO 下面都是一些可能的异常处理
			if (factoryMethodToUse == null || argsToUse == null) {
				// TODO 还是没有确定要使用的工厂方法时
				if (causes != null) {
					// TODO 要是有异常发生, 记录一下异常, 然后抛出
					UnsatisfiedDependencyException ex = causes.removeLast();
					for (Exception cause : causes) {
						this.beanFactory.onSuppressedException(cause);
					}
					throw ex;
				}
				// TODO 没有异常时
				List<String> argTypes = new ArrayList<>(minNrOfArgs);
				if (explicitArgs != null) {
					// TODO 如果指定了创建bean所需要的参数, 将其类型加入到参数类型缓存中
					for (Object arg : explicitArgs) {
						argTypes.add(arg != null ? arg.getClass().getSimpleName() : "null");
					}
				}
				else if (resolvedValues != null) {
					// TODO 没指定创建bean所需要的参数, 但是已经有了解析好的值时, 把解析好的值的索引参数, 以及泛型参数全部加到Set<ValueHolder>中
					Set<ValueHolder> valueHolders = CollectionUtils.newLinkedHashSet(resolvedValues.getArgumentCount());
					valueHolders.addAll(resolvedValues.getIndexedArgumentValues().values());
					valueHolders.addAll(resolvedValues.getGenericArgumentValues());
					for (ValueHolder value : valueHolders) {
						// TODO 然后将其中的参数类型全部加到参数类型缓存中
						String argType = (value.getType() != null ? ClassUtils.getShortName(value.getType()) :
								(value.getValue() != null ? value.getValue().getClass().getSimpleName() : "null"));
						argTypes.add(argType);
					}
				}
				// TODO 把最终的参数类型缓存转化为','分割的字符串, 然后抛出'No matching factory method found: '的BeanCreationException异常
				String argDesc = StringUtils.collectionToCommaDelimitedString(argTypes);
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"No matching factory method found on class [" + factoryClass.getName() + "]: " +
						(mbd.getFactoryBeanName() != null ? "factory bean '" + mbd.getFactoryBeanName() + "'; " : "") +
						"factory method '" + mbd.getFactoryMethodName() + "(" + argDesc + ")'. " +
						"Check that a method with the specified name " + (minNrOfArgs > 0 ? "and arguments " : "") +
						"exists and that it is " + (isStatic ? "static" : "non-static") + ".");
			}
			else if (void.class == factoryMethodToUse.getReturnType()) {
				// TODO 找到的工厂方法的返回类型是void时, 抛出'Invalid factory method'的BeanCreationException异常
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Invalid factory method '" + mbd.getFactoryMethodName() + "' on class [" +
						factoryClass.getName() + "]: needs to have a non-void return type!");
			}
			else if (KotlinDetector.isSuspendingFunction(factoryMethodToUse)) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Invalid factory method '" + mbd.getFactoryMethodName() + "' on class [" +
								factoryClass.getName() + "]: suspending functions are not supported!");
			}
			else if (ambiguousFactoryMethods != null) {
				// TODO 严格模式下出现有歧义的工厂方法时, 抛出'Ambiguous factory method matches found'的BeanCreationException异常
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Ambiguous factory method matches found on class [" + factoryClass.getName() + "] " +
						"(hint: specify index/type/name arguments for simple parameters to avoid type ambiguities): " +
						ambiguousFactoryMethods);
			}
			// TODO 没有异常发生时
			if (explicitArgs == null && argsHolderToUse != null) {
				// TODO 没指定创建bean时的参数, 且已经解析好构造函数的参数时, 把找到的工厂方法做为内省方法
				mbd.factoryMethodToIntrospect = factoryMethodToUse;
				// TODO 用解析好构造函数的参数以及找到的工厂方法来更新要实例化的bean的mbd的resolvedConstructorOrFactoryMethod,
				//  preparedConstructorArguments, 以及resolvedConstructorArguments缓存, 后续再使用时, 就不用重新解析了
				argsHolderToUse.storeCache(mbd, factoryMethodToUse);
			}
		}
		// TODO 当解析完工厂方法, 或者是曾经解析过了, 直接使用缓存中的工厂方法及参数时, 就可以开始进行实例化了. 实例化的结果会更新到
		//  用于返回的BeanWrapperImpl中
		bw.setBeanInstance(instantiate(beanName, mbd, factoryBean, factoryMethodToUse, argsToUse));
		return bw;
	}

	/**
	 *
	 * @param beanName 要实例化的bean
	 * @param mbd 要实例化的bean的mbd
	 * @param factoryBean 用于实例化bean的工厂类
	 * @param factoryMethod 用于实例化bean的工厂方法
	 * @param args 用于实例化bean的参数
	 * @return
	 */
	// TODO 通过容器的实例化策略调用工厂方法对bean进行实例化, 默认的实例化策略是SimpleInstantiationStrategy
	private Object instantiate(String beanName, RootBeanDefinition mbd,
			@Nullable Object factoryBean, Method factoryMethod, @Nullable Object[] args) {

		try {
			return this.beanFactory.getInstantiationStrategy().instantiate(
					mbd, beanName, this.beanFactory, factoryBean, factoryMethod, args);
		}
		catch (Throwable ex) {
			throw new BeanCreationException(mbd.getResourceDescription(), beanName, ex.getMessage(), ex);
		}
	}

	/**
	 * Resolve the constructor arguments for this bean into the resolvedValues object.
	 * This may involve looking up other beans.
	 * <p>This method is also used for handling invocations of static factory methods.
	 *
	 * @param beanName 是创建的bean的名字
	 * @param mbd 要创建的bean的mbd
	 * @param bw 用于返回的创建好的bean
	 * @param cargs 用于解析的构造器的参数
	 * @param resolvedValues 解析好的构造器的参数
	 * @return
	 */
	private int resolveConstructorArguments(String beanName, RootBeanDefinition mbd, BeanWrapper bw,
			ConstructorArgumentValues cargs, ConstructorArgumentValues resolvedValues) {
		// TODO 取得自定义的类型转换器
		TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
		TypeConverter converter = (customConverter != null ? customConverter : bw);
		// TODO 创建一个值解析器
		BeanDefinitionValueResolver valueResolver =
				new BeanDefinitionValueResolver(this.beanFactory, beanName, mbd, converter);
		// TODO 得到构造器的参数数量, 数量 = 带索引的参数数量 + 泛型参数数量
		int minNrOfArgs = cargs.getArgumentCount();

		for (Map.Entry<Integer, ConstructorArgumentValues.ValueHolder> entry : cargs.getIndexedArgumentValues().entrySet()) {
			// TODO 先遍历有索引的参数
			int index = entry.getKey();
			if (index < 0) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"Invalid constructor argument index: " + index);
			}
			if (index + 1 > minNrOfArgs) {
				// TODO 在索引 > 带索引的参数数量 + 泛型参数数量时, 用索引更新构造器的参数数量的最小值
				minNrOfArgs = index + 1;
			}
			// TODO 拿出当前位置的参数值
			ConstructorArgumentValues.ValueHolder valueHolder = entry.getValue();
			if (valueHolder.isConverted()) {
				// TODO 如果参数值已经解析过了, 就放到用于返回的ConstructorArgumentValues的包含索引的参数缓存中
				resolvedValues.addIndexedArgumentValue(index, valueHolder);
			}
			else {
				// TODO 否则解析参数值, 设置来源后, 放到用于返回的ConstructorArgumentValues的包含索引的参数缓存中
				Object resolvedValue =
						valueResolver.resolveValueIfNecessary("constructor argument", valueHolder.getValue());
				ConstructorArgumentValues.ValueHolder resolvedValueHolder =
						new ConstructorArgumentValues.ValueHolder(resolvedValue, valueHolder.getType(), valueHolder.getName());
				resolvedValueHolder.setSource(valueHolder);
				resolvedValues.addIndexedArgumentValue(index, resolvedValueHolder);
			}
		}

		for (ConstructorArgumentValues.ValueHolder valueHolder : cargs.getGenericArgumentValues()) {
			// TODO 然后再遍历泛型参数
			if (valueHolder.isConverted()) {
				// TODO 如果参数值已经解析过了, 就放到用于返回的ConstructorArgumentValues的泛型参数缓存中
				resolvedValues.addGenericArgumentValue(valueHolder);
			}
			else {
				// TODO 否则解析参数值, 设置来源后, 放到用于返回的ConstructorArgumentValues的泛型参数缓存中
				Object resolvedValue =
						valueResolver.resolveValueIfNecessary("constructor argument", valueHolder.getValue());
				ConstructorArgumentValues.ValueHolder resolvedValueHolder = new ConstructorArgumentValues.ValueHolder(
						resolvedValue, valueHolder.getType(), valueHolder.getName());
				resolvedValueHolder.setSource(valueHolder);
				resolvedValues.addGenericArgumentValue(resolvedValueHolder);
			}
		}

		return minNrOfArgs;
	}

	/**
	 * Create an array of arguments to invoke a constructor or factory method,
	 * given the resolved constructor argument values.
	 *
	 * @param beanName 要实例化的bean
	 * @param mbd 要实例化的bean的mbd
	 * @param resolvedValues 解析好的构造函数的参数
	 * @param bw 封装了用于返回的实例化的bean的BeanWrapper
	 * @param paramTypes 当前操作的工厂方法的参数类型数组
	 * @param paramNames 当前操作的工厂方法的参数名数组
	 * @param executable 当前操作的工厂方法
	 * @param autowiring 要实例化的bean是否可以自动装配
	 * @param fallback 回退标志
	 * @return
	 * @throws UnsatisfiedDependencyException
	 */
	private ArgumentsHolder createArgumentArray(
			String beanName, RootBeanDefinition mbd, @Nullable ConstructorArgumentValues resolvedValues,
			BeanWrapper bw, Class<?>[] paramTypes, @Nullable String @Nullable [] paramNames, Executable executable,
			boolean autowiring, boolean fallback) throws UnsatisfiedDependencyException {
		// TODO 取得容器中的自定义类型转换器, 如果没有就用用于返回的bw做为类型转换器
		TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
		TypeConverter converter = (customConverter != null ? customConverter : bw);
		// TODO 用于返回的参数
		ArgumentsHolder args = new ArgumentsHolder(paramTypes.length);
		// TODO 已经解析过的参数
		Set<ConstructorArgumentValues.ValueHolder> usedValueHolders = new HashSet<>(paramTypes.length);
		// TODO 自动装配过的bean
		Set<String> allAutowiredBeanNames = new LinkedHashSet<>(paramTypes.length * 2);

		// TODO 遍历方法的每个参数
		for (int paramIndex = 0; paramIndex < paramTypes.length; paramIndex++) {
			// TODO 取得对应位置上的参数类型
			Class<?> paramType = paramTypes[paramIndex];
			// TODO 取得对应位置上的参数名, 如果没有就是""空字符串
			String paramName = (paramNames != null ? paramNames[paramIndex] : "");
			// Try to find matching constructor argument value, either indexed or generic.
			ConstructorArgumentValues.ValueHolder valueHolder = null;
			if (resolvedValues != null) {
				// TODO 从解析好的构造函数参数中拿出对应位置的值
				valueHolder = resolvedValues.getArgumentValue(paramIndex, paramType, paramName, usedValueHolders);
				// If we couldn't find a direct match and are not supposed to autowire,
				// let's try the next generic, untyped argument value as fallback:
				// it could match after type conversion (for example, String -> int).
				if (valueHolder == null && (!autowiring || paramTypes.length == resolvedValues.getArgumentCount())) {
					// TODO 没有找到直接匹配的对应的参数值, 同时还不支持自动装配时, 做为回退方式, 尝试下一个泛型, 以及元类型的参数值
					//  即, 不匹配类型以及名字
					valueHolder = resolvedValues.getGenericArgumentValue(null, null, usedValueHolders);
				}
			}
			if (valueHolder != null) {
				// We found a potential match - let's give it a try.
				// Do not consider the same value definition multiple times!
				// TODO 把找到的值加入到已使用值的集合中, 之后再出现同样的参数就不用解析了
				usedValueHolders.add(valueHolder);
				Object originalValue = valueHolder.getValue();
				Object convertedValue;
				if (valueHolder.isConverted()) {
					// TODO 对于已经转换过的参数值来说, 把他加入到preparedArguments已准备好的参数缓存中
					convertedValue = valueHolder.getConvertedValue();
					args.preparedArguments[paramIndex] = convertedValue;
				}
				else {
					MethodParameter methodParam = MethodParameter.forExecutable(executable, paramIndex);
					try {
						// TODO 对于没转换过的, 从方法(工厂方法)中取出对应位置的方法参数, 然后转换为参数对应的类型
						convertedValue = converter.convertIfNecessary(originalValue, paramType, methodParam);
					}
					catch (TypeMismatchException ex) {
						throw new UnsatisfiedDependencyException(
								mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam),
								"Could not convert argument value of type [" +
								ObjectUtils.nullSafeClassName(valueHolder.getValue()) +
								"] to required type [" + paramType.getName() + "]: " + ex.getMessage());
					}
					Object sourceHolder = valueHolder.getSource();
					if (sourceHolder instanceof ConstructorArgumentValues.ValueHolder constructorValueHolder) {
						// TODO 如果参数的值是ValueHolder, 将其来源放到preparedArguments已准备好的参数缓存中, 并且标识需要解析
						Object sourceValue = constructorValueHolder.getValue();
						args.resolveNecessary = true;
						args.preparedArguments[paramIndex] = sourceValue;
					}
				}
				// TODO 把转换后的值, 以及原始值都放到用于返回的结果中
				args.arguments[paramIndex] = convertedValue;
				args.rawArguments[paramIndex] = originalValue;
			}
			else {
				// TODO 没找到参数值时, 先拿出对应位置的方法参数
				MethodParameter methodParam = MethodParameter.forExecutable(executable, paramIndex);
				// No explicit match found: we're either supposed to autowire or
				// have to fail creating an argument array for the given constructor.
				if (!autowiring) {
					// TODO 如果不支持自动装配, 抛出UnsatisfiedDependencyException异常
					throw new UnsatisfiedDependencyException(
							mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam),
							"Ambiguous argument values for parameter of type [" + paramType.getName() +
							"] - did you specify the correct bean references as arguments?");
				}
				try {
					ConstructorDependencyDescriptor desc = new ConstructorDependencyDescriptor(methodParam, true);
					Set<String> autowiredBeanNames = new LinkedHashSet<>(2);
					// TODO 支持自动装配时, 会进行自动装配处理
					Object arg = resolveAutowiredArgument(
							desc, paramType, beanName, autowiredBeanNames, converter, fallback);
					if (arg != null) {
						setShortcutIfPossible(desc, paramType, autowiredBeanNames);
					}
					allAutowiredBeanNames.addAll(autowiredBeanNames);
					// TODO 原生参数, 以及参数都设置为自动装配解析后的参数
					args.rawArguments[paramIndex] = arg;
					args.arguments[paramIndex] = arg;
					// TODO 设置自动装配参数标示
					args.preparedArguments[paramIndex] = desc;
					args.resolveNecessary = true;
				}
				catch (BeansException ex) {
					throw new UnsatisfiedDependencyException(
							mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam), ex);
				}
			}
		}

		registerDependentBeans(executable, beanName, allAutowiredBeanNames);

		return args;
	}

	/**
	 * Resolve the prepared arguments stored in the given bean definition.
	 *
	 * @param beanName 要创建实例的 bean 名
	 * @param mbd 要创建实例的 bean的 mbd
	 * @param bw 用于返回的, 包装bean实例的的 BeanWrapper
	 * @param executable 解析过的用于实例化 bean 的构造函数, 或工厂方法
	 * @param argsToResolve 准备解析的参数数组
	 * @return 解析好的参数
	 *         1. @Autowire: 符合要求的注入 bean
	 *         2. Property: 解析好的 property, 或 propertyValue 中的值
	 *         3. String: 评估解析, 因为其有可能是SpEL表达式
	 */
	// TODO 按顺序解析参数
	private @Nullable Object[] resolvePreparedArguments(String beanName, RootBeanDefinition mbd, BeanWrapper bw,
			Executable executable, Object[] argsToResolve) {
		// TODO 取得容器中的自定义类型转换器
		TypeConverter customConverter = this.beanFactory.getCustomTypeConverter();
		// TODO 如果容器中没有自定义的类型转换器, 使用包装 bean 实例的 BeanWrapper 做为类型转换器
		TypeConverter converter = (customConverter != null ? customConverter : bw);
		// TODO 为要创建实例的bean创建一个解析 propertyValues 的值解析器
		BeanDefinitionValueResolver valueResolver =
				new BeanDefinitionValueResolver(this.beanFactory, beanName, mbd, converter);
		// TODO 按顺序取出用于实例化 bean 的构造函数, 或工厂方法所使用的全部参数的参数类型( executable 可能是 factory-method 指定的工厂方法)
		Class<?>[] paramTypes = executable.getParameterTypes();
		// TODO 这里是用来放解析好的参数用的
		@Nullable Object[] resolvedArgs = new Object[argsToResolve.length];
		for (int argIndex = 0; argIndex < argsToResolve.length; argIndex++) {
			// TODO 按顺序挨个解析参数, 先取得要解析的参数的值
			Object argValue = argsToResolve[argIndex];
			// TODO 取得用于实例化 bean 的构造函数, 或工厂方法对应位置的参数类型
			Class<?> paramType = paramTypes[argIndex];
			boolean convertNecessary = false;
			if (argValue instanceof ConstructorDependencyDescriptor descriptor) {
				try {
					argValue = resolveAutowiredArgument(descriptor, paramType, beanName,
							null, converter, true);
				}
				catch (BeansException ex) {
					// Unexpected target bean mismatch for cached argument -> re-resolve
					Set<String> autowiredBeanNames = null;
					if (descriptor.hasShortcut()) {
						// Reset shortcut and try to re-resolve it in this thread...
						descriptor.setShortcut(null);
						autowiredBeanNames = new LinkedHashSet<>(2);
					}
					logger.debug("Failed to resolve cached argument", ex);
					// TODO 参数是Object时, 表示需要进行自动装配处理. 比如下面这种情况:
					//  public String getCar(@Autowire Car car) {
					//  }
					//  MethodParameter里封装的就是方法(工厂方法, 或构造函数), 以及第一个参数Car的信息. 在解析自动装配时, 会把符合要求
					//  的bean(Car类型的bean)拿出来做为参数的值argValue. 如果注入的是一个集合, 数组, Map等多值对象, 这里会返回Object[]
					argValue = resolveAutowiredArgument(descriptor, paramType, beanName,
							autowiredBeanNames, converter, true);
					if (autowiredBeanNames != null && !descriptor.hasShortcut()) {
						// We encountered as stale shortcut before, and the shortcut has
						// not been re-resolved by another thread in the meantime...
						if (argValue != null) {
							setShortcutIfPossible(descriptor, paramType, autowiredBeanNames);
						}
						registerDependentBeans(executable, beanName, autowiredBeanNames);
					}
				}
			}
			else if (argValue instanceof BeanMetadataElement) {
				// TODO 参数是BeanMetadataElement类型时, 解析propertyValues
				argValue = valueResolver.resolveValueIfNecessary("constructor argument", argValue);
				convertNecessary = true;
			}
			else if (argValue instanceof String text) {
				// TODO 参数是字符串时, 有可能是SpEL表达式, 所以对其进行评估解析
				argValue = this.beanFactory.evaluateBeanDefinitionString(text, mbd);
				convertNecessary = true;
			}
			if (convertNecessary) {
				MethodParameter methodParam = MethodParameter.forExecutable(executable, argIndex);
				try {
					// TODO 对要解析的参数的值进行必要的类型转换(转换为方法对应位置的参数的类型. Converter就是从这里起作用的):
					//  1. DataBinder:
					//  2. TypeConverterSupport:
					argValue = converter.convertIfNecessary(argValue, paramType, methodParam);
				}
				catch (TypeMismatchException ex) {
					throw new UnsatisfiedDependencyException(
							mbd.getResourceDescription(), beanName, new InjectionPoint(methodParam),
							"Could not convert argument value of type [" + ObjectUtils.nullSafeClassName(argValue) +
							"] to required type [" + paramType.getName() + "]: " + ex.getMessage());
				}
			}
			resolvedArgs[argIndex] = argValue;
		}
		// TODO 全处理完后, 返回解析后的参数数组
		return resolvedArgs;
	}

	// TODO 取得用户声明的构造器, 如果构造器所在的类是由CGLib代理过的, 则取得其原始类中同类型的构造器
	private Constructor<?> getUserDeclaredConstructor(Constructor<?> constructor) {
		// TODO 先得到构造器所在的类
		Class<?> declaringClass = constructor.getDeclaringClass();
		// TODO 因为构造器所在的类可能是由CGLib创建的代理类, 所以再处理一下
		Class<?> userClass = ClassUtils.getUserClass(declaringClass);
		if (userClass != declaringClass) {
			try {
				// TODO 如果构造器所在的类是由CGLib创建的代理类, 则取得原始类中的构造器
				return userClass.getDeclaredConstructor(constructor.getParameterTypes());
			}
			catch (NoSuchMethodException ex) {
				// No equivalent constructor on user class (superclass)...
				// Let's proceed with the given constructor as we usually would.
			}
		}
		return constructor;
	}

	/**
	 * Resolve the specified argument which is supposed to be autowired.
	 *
	 * @param descriptor
	 * @param paramType
	 * @param beanName 要创建实例的 bean, 即要得到的 bean
	 * @param autowiredBeanNames 自动装配过的 bea n集合
	 * @param typeConverter 类型转换器
	 * @param fallback
	 * @return
	 */
	@Nullable Object resolveAutowiredArgument(DependencyDescriptor descriptor, Class<?> paramType, String beanName,
			@Nullable Set<String> autowiredBeanNames, TypeConverter typeConverter, boolean fallback) {

		if (InjectionPoint.class.isAssignableFrom(paramType)) {
			// TODO 如果方法的参数类型是 InjectionPoint 类型, 直接从当前线程中取得注入点返回, 如果没有注入点, 则抛出异常
			InjectionPoint injectionPoint = currentInjectionPoint.get();
			if (injectionPoint == null) {
				throw new IllegalStateException("No current InjectionPoint available for " + descriptor);
			}
			return injectionPoint;
		}

		try {
			// TODO 其他情况就需要解析一下依赖:
			//  1. 将方法参数(用于实例化 bean 的方法参数)封装为一个 DependencyDescriptor. DependencyDescriptor 是 InjectionPoint的子类,
			//     用于描述一个用于注入的依赖项的描述符, 比如: 字段(成员属性), 或方法(普通方法, 构造函数). 对于 DependencyDescriptor
			//     描述的项来说, 可以对其进行自动装配, 即: 方法的参数也可以使用 @Autowaire, @Value 这些注解来进行自动注入.
			//     TIPS: 因为这里要处理的是方法(工厂方法, 构造函数), 所以使用的是 DependencyDescriptor(MethodParameter, boolean)
			//           构造函数. 因此用于实例化 bean 的方法参数(工厂方法, 或构造函数的参数)会设置到父类 InjectionPoint$methodParameter 的属性中.
			//           而与字段(成员属性)相关的属性( DependencyDescriptor$fieldName, InjectionPoint$field ), 则会是null
			//  2. 在当前容器中解析依赖关系
			return this.beanFactory.resolveDependency(descriptor, beanName, autowiredBeanNames, typeConverter);
		}
		catch (NoUniqueBeanDefinitionException ex) {
			// TODO 向外传递解析过程中出现多个候选bean的NoUniqueBeanDefinitionException异常
			throw ex;
		}
		catch (NoSuchBeanDefinitionException ex) {
			if (fallback) {
				// Single constructor or factory method -> let's return an empty array/collection
				// for example, a vararg or a non-null List/Set/Map parameter.
				// TODO 遇到NoSuchBeanDefinitionException异常时, 如果支持回退, 则会为数组, 集合, Map类型创建空对象并返回
				if (paramType.isArray()) {
					return Array.newInstance(paramType.componentType(), 0);
				}
				else if (CollectionFactory.isApproximableCollectionType(paramType)) {
					return CollectionFactory.createCollection(paramType, 0);
				}
				else if (CollectionFactory.isApproximableMapType(paramType)) {
					return CollectionFactory.createMap(paramType, 0);
				}
			}
			// TODO 否则, 向外传递异常
			throw ex;
		}
	}

	private void setShortcutIfPossible(
			ConstructorDependencyDescriptor descriptor, Class<?> paramType, Set<String> autowiredBeanNames) {

		if (autowiredBeanNames.size() == 1) {
			String autowiredBeanName = autowiredBeanNames.iterator().next();
			if (this.beanFactory.containsBean(autowiredBeanName) &&
					this.beanFactory.isTypeMatch(autowiredBeanName, paramType)) {
				descriptor.setShortcut(autowiredBeanName);
			}
		}
	}

	private void registerDependentBeans(
			Executable executable, String beanName, Set<String> autowiredBeanNames) {

		for (String autowiredBeanName : autowiredBeanNames) {
			// TODO 注册一下要实例化的bean与自动装配项之前的关系:
			//  1. dependentBeanMap: 自动装配项被要实例化的bean所依赖
			//  2. dependenciesForBeanMap: 要实例化的bean依赖哪些自动装配项
			this.beanFactory.registerDependentBean(autowiredBeanName, beanName);
			if (logger.isDebugEnabled()) {
				logger.debug("Autowiring by type from bean name '" + beanName + "' via " +
						(executable instanceof Constructor ? "constructor" : "factory method") +
						" to bean named '" + autowiredBeanName + "'");
			}
		}
	}


	// AOT-oriented pre-resolution

	public Executable resolveConstructorOrFactoryMethod(String beanName, RootBeanDefinition mbd) {
		Supplier<ResolvableType> beanType = () -> getBeanType(beanName, mbd);
		List<ResolvableType> valueTypes = (mbd.hasConstructorArgumentValues() ?
				determineParameterValueTypes(mbd) : Collections.emptyList());
		Method resolvedFactoryMethod = resolveFactoryMethod(beanName, mbd, valueTypes);
		if (resolvedFactoryMethod != null) {
			return resolvedFactoryMethod;
		}

		Class<?> factoryBeanClass = getFactoryBeanClass(beanName, mbd);
		if (factoryBeanClass != null && !factoryBeanClass.equals(mbd.getResolvableType().toClass())) {
			ResolvableType resolvableType = mbd.getResolvableType();
			boolean isCompatible = ResolvableType.forClass(factoryBeanClass)
					.as(FactoryBean.class).getGeneric(0).isAssignableFrom(resolvableType);
			Assert.state(isCompatible, () -> String.format(
					"Incompatible target type '%s' for factory bean '%s'",
					resolvableType.toClass().getName(), factoryBeanClass.getName()));
			Constructor<?> constructor = resolveConstructor(beanName, mbd,
					() -> ResolvableType.forClass(factoryBeanClass), valueTypes);
			if (constructor != null) {
				return constructor;
			}
			throw new IllegalStateException("No suitable FactoryBean constructor found for " +
					mbd + " and argument types " + valueTypes);

		}

		Constructor<?> constructor = resolveConstructor(beanName, mbd, beanType, valueTypes);
		if (constructor != null) {
			return constructor;
		}

		throw new IllegalStateException("No constructor or factory method candidate found for " +
				mbd + " and argument types " + valueTypes);
	}

	private List<ResolvableType> determineParameterValueTypes(RootBeanDefinition mbd) {
		List<ResolvableType> parameterTypes = new ArrayList<>();
		for (ValueHolder valueHolder : mbd.getConstructorArgumentValues().getIndexedArgumentValues().values()) {
			parameterTypes.add(determineParameterValueType(mbd, valueHolder));
		}
		for (ValueHolder valueHolder : mbd.getConstructorArgumentValues().getGenericArgumentValues()) {
			parameterTypes.add(determineParameterValueType(mbd, valueHolder));
		}
		return parameterTypes;
	}

	private ResolvableType determineParameterValueType(RootBeanDefinition mbd, ValueHolder valueHolder) {
		if (valueHolder.getType() != null) {
			return ResolvableType.forClass(
					ClassUtils.resolveClassName(valueHolder.getType(), this.beanFactory.getBeanClassLoader()));
		}
		Object value = valueHolder.getValue();
		if (value instanceof BeanReference br) {
			if (value instanceof RuntimeBeanReference rbr) {
				if (rbr.getBeanType() != null) {
					return ResolvableType.forClass(rbr.getBeanType());
				}
			}
			return ResolvableType.forClass(this.beanFactory.getType(br.getBeanName(), false));
		}
		if (value instanceof BeanDefinition innerBd) {
			String nameToUse = "(inner bean)";
			ResolvableType type = getBeanType(nameToUse,
					this.beanFactory.getMergedBeanDefinition(nameToUse, innerBd, mbd));
			return (FactoryBean.class.isAssignableFrom(type.toClass()) ?
					type.as(FactoryBean.class).getGeneric(0) : type);
		}
		if (value instanceof TypedStringValue typedValue) {
			if (typedValue.hasTargetType()) {
				return ResolvableType.forClass(typedValue.getTargetType());
			}
			return ResolvableType.forClass(String.class);
		}
		if (value instanceof Class<?> clazz) {
			return ResolvableType.forClassWithGenerics(Class.class, clazz);
		}
		return ResolvableType.forInstance(value);
	}

	private @Nullable Constructor<?> resolveConstructor(String beanName, RootBeanDefinition mbd,
			Supplier<ResolvableType> beanType, List<ResolvableType> valueTypes) {

		Class<?> type = ClassUtils.getUserClass(beanType.get().toClass());
		Constructor<?>[] ctors = this.beanFactory.determineConstructorsFromBeanPostProcessors(type, beanName);
		if (ctors == null) {
			if (!mbd.hasConstructorArgumentValues()) {
				ctors = mbd.getPreferredConstructors();
			}
			if (ctors == null) {
				ctors = (mbd.isNonPublicAccessAllowed() ? type.getDeclaredConstructors() : type.getConstructors());
			}
		}
		if (ctors.length == 1) {
			return ctors[0];
		}

		Function<Constructor<?>, List<ResolvableType>> parameterTypesFactory = executable -> {
			List<ResolvableType> types = new ArrayList<>();
			for (int i = 0; i < executable.getParameterCount(); i++) {
				types.add(ResolvableType.forConstructorParameter(executable, i));
			}
			return types;
		};
		List<Constructor<?>> matches = Arrays.stream(ctors)
				.filter(executable -> match(parameterTypesFactory.apply(executable),
						valueTypes, FallbackMode.NONE))
				.toList();
		if (matches.size() == 1) {
			return matches.get(0);
		}
		List<Constructor<?>> assignableElementFallbackMatches = Arrays
				.stream(ctors)
				.filter(executable -> match(parameterTypesFactory.apply(executable),
						valueTypes, FallbackMode.ASSIGNABLE_ELEMENT))
				.toList();
		if (assignableElementFallbackMatches.size() == 1) {
			return assignableElementFallbackMatches.get(0);
		}
		List<Constructor<?>> typeConversionFallbackMatches = Arrays
				.stream(ctors)
				.filter(executable -> match(parameterTypesFactory.apply(executable),
						valueTypes, FallbackMode.TYPE_CONVERSION))
				.toList();
		return (typeConversionFallbackMatches.size() == 1 ? typeConversionFallbackMatches.get(0) : null);
	}

	private @Nullable Method resolveFactoryMethod(String beanName, RootBeanDefinition mbd, List<ResolvableType> valueTypes) {
		if (mbd.isFactoryMethodUnique) {
			Method resolvedFactoryMethod = mbd.getResolvedFactoryMethod();
			if (resolvedFactoryMethod != null) {
				return resolvedFactoryMethod;
			}
		}

		String factoryMethodName = mbd.getFactoryMethodName();
		if (factoryMethodName != null) {
			String factoryBeanName = mbd.getFactoryBeanName();
			Class<?> factoryClass;
			boolean isStatic;
			if (factoryBeanName != null) {
				factoryClass = this.beanFactory.getType(factoryBeanName);
				isStatic = false;
			}
			else {
				factoryClass = this.beanFactory.resolveBeanClass(mbd, beanName);
				isStatic = true;
			}

			Assert.state(factoryClass != null, () -> "Failed to determine bean class of " + mbd);
			Method[] rawCandidates = getCandidateMethods(factoryClass, mbd);
			List<Method> candidates = new ArrayList<>();
			for (Method candidate : rawCandidates) {
				if ((!isStatic || isStaticCandidate(candidate, factoryClass)) && mbd.isFactoryMethod(candidate)) {
					candidates.add(candidate);
				}
			}

			Method result = null;
			if (candidates.size() == 1) {
				result = candidates.get(0);
			}
			else if (candidates.size() > 1) {
				Function<Method, List<ResolvableType>> parameterTypesFactory = method -> {
					List<ResolvableType> types = new ArrayList<>();
					for (int i = 0; i < method.getParameterCount(); i++) {
						types.add(ResolvableType.forMethodParameter(method, i));
					}
					return types;
				};
				result = resolveFactoryMethod(candidates, parameterTypesFactory, valueTypes);
			}

			if (result == null) {
				throw new BeanCreationException(mbd.getResourceDescription(), beanName,
						"No matching factory method found on class [" + factoryClass.getName() + "]: " +
						(mbd.getFactoryBeanName() != null ?
								"factory bean '" + mbd.getFactoryBeanName() + "'; " : "") +
						"factory method '" + mbd.getFactoryMethodName() + "'. ");
			}
			return result;
		}

		return null;
	}

	private @Nullable Method resolveFactoryMethod(List<Method> executables,
			Function<Method, List<ResolvableType>> parameterTypesFactory,
			List<ResolvableType> valueTypes) {

		List<Method> matches = executables.stream()
				.filter(executable -> match(parameterTypesFactory.apply(executable), valueTypes, FallbackMode.NONE))
				.toList();
		if (matches.size() == 1) {
			return matches.get(0);
		}
		List<Method> assignableElementFallbackMatches = executables.stream()
				.filter(executable -> match(parameterTypesFactory.apply(executable),
						valueTypes, FallbackMode.ASSIGNABLE_ELEMENT))
				.toList();
		if (assignableElementFallbackMatches.size() == 1) {
			return assignableElementFallbackMatches.get(0);
		}
		List<Method> typeConversionFallbackMatches = executables.stream()
				.filter(executable -> match(parameterTypesFactory.apply(executable),
						valueTypes, FallbackMode.TYPE_CONVERSION))
				.toList();
		Assert.state(typeConversionFallbackMatches.size() <= 1,
				() -> "Multiple matches with parameters '" + valueTypes + "': " + typeConversionFallbackMatches);
		return (typeConversionFallbackMatches.size() == 1 ? typeConversionFallbackMatches.get(0) : null);
	}

	private boolean match(
			List<ResolvableType> parameterTypes, List<ResolvableType> valueTypes, FallbackMode fallbackMode) {

		if (parameterTypes.size() != valueTypes.size()) {
			return false;
		}
		for (int i = 0; i < parameterTypes.size(); i++) {
			if (!isMatch(parameterTypes.get(i), valueTypes.get(i), fallbackMode)) {
				return false;
			}
		}
		return true;
	}

	private boolean isMatch(ResolvableType parameterType, ResolvableType valueType, FallbackMode fallbackMode) {
		if (isAssignable(valueType).test(parameterType)) {
			return true;
		}
		return switch (fallbackMode) {
			case ASSIGNABLE_ELEMENT -> isAssignable(valueType).test(extractElementType(parameterType));
			case TYPE_CONVERSION -> typeConversionFallback(valueType).test(parameterType);
			default -> false;
		};
	}

	private Predicate<ResolvableType> isAssignable(ResolvableType valueType) {
		return parameterType -> (valueType == ResolvableType.NONE || parameterType.isAssignableFrom(valueType));
	}

	private ResolvableType extractElementType(ResolvableType parameterType) {
		if (parameterType.isArray()) {
			return parameterType.getComponentType();
		}
		if (Collection.class.isAssignableFrom(parameterType.toClass())) {
			return parameterType.as(Collection.class).getGeneric(0);
		}
		return ResolvableType.NONE;
	}

	private Predicate<ResolvableType> typeConversionFallback(ResolvableType valueType) {
		return parameterType -> {
			if (valueOrCollection(valueType, this::isStringForClassFallback).test(parameterType)) {
				return true;
			}
			return valueOrCollection(valueType, this::isSimpleValueType).test(parameterType);
		};
	}

	private Predicate<ResolvableType> valueOrCollection(ResolvableType valueType,
			Function<ResolvableType, Predicate<ResolvableType>> predicateProvider) {

		return parameterType -> {
			if (predicateProvider.apply(valueType).test(parameterType)) {
				return true;
			}
			if (predicateProvider.apply(extractElementType(valueType)).test(extractElementType(parameterType))) {
				return true;
			}
			return (predicateProvider.apply(valueType).test(extractElementType(parameterType)));
		};
	}

	/**
	 * Return a {@link Predicate} for a parameter type that checks if its target
	 * value is a {@link Class} and the value type is a {@link String}. This is
	 * a regular use case where a {@link Class} is defined in the bean definition
	 * as a fully-qualified class name.
	 * @param valueType the type of the value
	 * @return a predicate to indicate a fallback match for a String to Class
	 * parameter
	 */
	private Predicate<ResolvableType> isStringForClassFallback(ResolvableType valueType) {
		return parameterType -> (valueType.isAssignableFrom(String.class) &&
				parameterType.isAssignableFrom(Class.class));
	}

	private Predicate<ResolvableType> isSimpleValueType(ResolvableType valueType) {
		return parameterType -> (BeanUtils.isSimpleValueType(parameterType.toClass()) &&
				BeanUtils.isSimpleValueType(valueType.toClass()));
	}

	private @Nullable Class<?> getFactoryBeanClass(String beanName, RootBeanDefinition mbd) {
		Class<?> beanClass = this.beanFactory.resolveBeanClass(mbd, beanName);
		return (beanClass != null && FactoryBean.class.isAssignableFrom(beanClass) ? beanClass : null);
	}

	private ResolvableType getBeanType(String beanName, RootBeanDefinition mbd) {
		ResolvableType resolvableType = mbd.getResolvableType();
		if (resolvableType != ResolvableType.NONE) {
			return resolvableType;
		}
		return ResolvableType.forClass(this.beanFactory.resolveBeanClass(mbd, beanName));
	}


	static InjectionPoint setCurrentInjectionPoint(@Nullable InjectionPoint injectionPoint) {
		InjectionPoint old = currentInjectionPoint.get();
		if (injectionPoint != null) {
			currentInjectionPoint.set(injectionPoint);
		}
		else {
			currentInjectionPoint.remove();
		}
		return old;
	}

	/**
	 * See {@link BeanUtils#getResolvableConstructor(Class)} for alignment.
	 * This variant adds a lenient fallback to the default constructor if available, similar to
	 * {@link org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor#determineCandidateConstructors}.
	 */
	static Constructor<?> @Nullable [] determinePreferredConstructors(Class<?> clazz) {
		Constructor<?> primaryCtor = BeanUtils.findPrimaryConstructor(clazz);

		Constructor<?> defaultCtor;
		try {
			defaultCtor = clazz.getDeclaredConstructor();
		}
		catch (NoSuchMethodException ex) {
			defaultCtor = null;
		}

		if (primaryCtor != null) {
			if (defaultCtor != null && !primaryCtor.equals(defaultCtor)) {
				return new Constructor<?>[] {primaryCtor, defaultCtor};
			}
			else {
				return new Constructor<?>[] {primaryCtor};
			}
		}

		Constructor<?>[] ctors = clazz.getConstructors();
		if (ctors.length == 1) {
			// A single public constructor, potentially in combination with a non-public default constructor
			if (defaultCtor != null && !ctors[0].equals(defaultCtor)) {
				return new Constructor<?>[] {ctors[0], defaultCtor};
			}
			else {
				return ctors;
			}
		}
		else if (ctors.length == 0) {
			// No public constructors -> check non-public
			ctors = clazz.getDeclaredConstructors();
			if (ctors.length == 1) {
				// A single non-public constructor, for example, from a non-public record type
				return ctors;
			}
		}

		return null;
	}


	/**
	 * Private inner class for holding argument combinations.
	 */
	private static class ArgumentsHolder {

		public final @Nullable Object[] rawArguments;

		public final @Nullable Object[] arguments;

		public final @Nullable Object[] preparedArguments;

		public boolean resolveNecessary = false;

		public ArgumentsHolder(int size) {
			this.rawArguments = new Object[size];
			this.arguments = new Object[size];
			this.preparedArguments = new Object[size];
		}

		public ArgumentsHolder(@Nullable Object[] args) {
			this.rawArguments = args;
			this.arguments = args;
			this.preparedArguments = args;
		}

		/**
		 * 取得方法参数类型的权重, 这里会衡量方法参数类型与参数的类型, 及参数的原生类型之前的最小权重值
		 *
		 * @param paramTypes 方法的所有参数类型的数组
		 * @return 方法参数的类型以及参数的类型, 和原生参数类型之前的权重最小值
		 */
		public int getTypeDifferenceWeight(Class<?>[] paramTypes) {
			// If valid arguments found, determine type difference weight.
			// Try type difference weight on both the converted arguments and
			// the raw arguments. If the raw weight is better, use it.
			// Decrease raw weight by 1024 to prefer it over equal converted weight.
			// TODO 先与转换后的参数做计算
			int typeDiffWeight = MethodInvoker.getTypeDifferenceWeight(paramTypes, this.arguments);
			// TODO 再与原始参数做计算, 对于原始参数类型的权重会-1024
			int rawTypeDiffWeight = MethodInvoker.getTypeDifferenceWeight(paramTypes, this.rawArguments) - 1024;
			// TODO 然后返回两个权重的最小值, 值越小越接近本身的类型
			return Math.min(rawTypeDiffWeight, typeDiffWeight);
		}

		/**
		 *
		 * @param paramTypes  方法的所有参数类型的数组
		 * @return
		 */
		public int getAssignabilityWeight(Class<?>[] paramTypes) {
			for (int i = 0; i < paramTypes.length; i++) {
				if (!ClassUtils.isAssignableValue(paramTypes[i], this.arguments[i])) {
					// TODO 比较每一个方法参数的类型与构造函数参数的类型, 只要不相同, 就返回int最大值
					return Integer.MAX_VALUE;
				}
			}
			for (int i = 0; i < paramTypes.length; i++) {
				if (!ClassUtils.isAssignableValue(paramTypes[i], this.rawArguments[i])) {
					// TODO 比较每一个方法参数的类型与构造函数参数的类型, 只要不相同, 就返回int最大值 - 512
					return Integer.MAX_VALUE - 512;
				}
			}
			// TODO 全匹配时, 意味着这个函数在多个构造函数参数为父子类或实现类的关系时, 全部返回相同的值, Integer.MAX_VALUE - 1024.
			//  这样返回统一的数字相等，spring就会认为存在有歧义的函数，不能确定使用哪一个。
			return Integer.MAX_VALUE - 1024;
		}

		public void storeCache(RootBeanDefinition mbd, Executable constructorOrFactoryMethod) {
			synchronized (mbd.constructorArgumentLock) {
				// TODO 缓存解析好的构造器, 或工厂方法
				mbd.resolvedConstructorOrFactoryMethod = constructorOrFactoryMethod;
				// TODO 然后表示构造器参数已经解析完毕了
				mbd.constructorArgumentsResolved = true;
				if (this.resolveNecessary) {
					// TODO 如果当前ArgumentsHolder持有的参数需要解析时, 将准备好要解析的参数设置到要实例化的bean的mbd中
					mbd.preparedConstructorArguments = this.preparedArguments;
				}
				else {
					// TODO 没有参数要解析时, 就把参数做为实例化bean时所需要的参数保存到bean的mbd的缓存中
					mbd.resolvedConstructorArguments = this.arguments;
				}
			}
		}
	}


	/**
	 * Delegate for checking Java's {@link ConstructorProperties} annotation.
	 */
	private static class ConstructorPropertiesChecker {

		public static String @Nullable [] evaluate(Constructor<?> candidate, int paramCount) {
			// TODO 取得候选方法上的@ConstructorProperties注解
			ConstructorProperties cp = candidate.getAnnotation(ConstructorProperties.class);
			if (cp != null) {
				// TODO 如果找到的话, 把其中的value值拿出来返回
				String[] names = cp.value();
				if (names.length != paramCount) {
					// TODO value值的数量少于要求的参数数量时, 抛出异常
					throw new IllegalStateException("Constructor annotated with @ConstructorProperties but not " +
							"corresponding to actual number of parameters (" + paramCount + "): " + candidate);
				}
				return names;
			}
			else {
				// TODO 没找到直接返回null
				return null;
			}
		}
	}


	/**
	 * DependencyDescriptor marker for constructor arguments,
	 * for differentiating between a provided DependencyDescriptor instance
	 * and an internally built DependencyDescriptor for autowiring purposes.
	 */
	@SuppressWarnings("serial")
	private static class ConstructorDependencyDescriptor extends DependencyDescriptor {

		private volatile @Nullable String shortcut;

		public ConstructorDependencyDescriptor(MethodParameter methodParameter, boolean required) {
			super(methodParameter, required);
		}

		public void setShortcut(@Nullable String shortcut) {
			this.shortcut = shortcut;
		}

		public boolean hasShortcut() {
			return (this.shortcut != null);
		}

		@Override
		public @Nullable Object resolveShortcut(BeanFactory beanFactory) {
			String shortcut = this.shortcut;
			return (shortcut != null ? beanFactory.getBean(shortcut, getDependencyType()) : null);
		}

		@Override
		public boolean usesStandardBeanLookup() {
			return true;
		}
	}


	private enum FallbackMode {

		NONE,

		ASSIGNABLE_ELEMENT,

		TYPE_CONVERSION
	}

}
