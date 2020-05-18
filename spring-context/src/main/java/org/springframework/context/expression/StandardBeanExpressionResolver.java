/*
 * Copyright 2002-2020 the original author or authors.
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

package org.springframework.context.expression;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanExpressionException;
import org.springframework.beans.factory.config.BeanExpressionContext;
import org.springframework.beans.factory.config.BeanExpressionResolver;
import org.springframework.core.convert.ConversionService;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.ParserContext;
import org.springframework.expression.spel.SpelParserConfiguration;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.expression.spel.support.StandardTypeConverter;
import org.springframework.expression.spel.support.StandardTypeLocator;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Standard implementation of the
 * {@link org.springframework.beans.factory.config.BeanExpressionResolver}
 * interface, parsing and evaluating Spring EL using Spring's expression module.
 *
 * <p>All beans in the containing {@code BeanFactory} are made available as
 * predefined variables with their common bean name, including standard context
 * beans such as "environment", "systemProperties" and "systemEnvironment".
 *
 * @author Juergen Hoeller
 * @since 3.0
 * @see BeanExpressionContext#getBeanFactory()
 * @see org.springframework.expression.ExpressionParser
 * @see org.springframework.expression.spel.standard.SpelExpressionParser
 * @see org.springframework.expression.spel.support.StandardEvaluationContext
 */
public class StandardBeanExpressionResolver implements BeanExpressionResolver {

	/** Default expression prefix: "#{". */
	public static final String DEFAULT_EXPRESSION_PREFIX = "#{";

	/** Default expression suffix: "}". */
	public static final String DEFAULT_EXPRESSION_SUFFIX = "}";


	private String expressionPrefix = DEFAULT_EXPRESSION_PREFIX;

	private String expressionSuffix = DEFAULT_EXPRESSION_SUFFIX;

	private ExpressionParser expressionParser;
	// TODO Expression表达式缓存
	private final Map<String, Expression> expressionCache = new ConcurrentHashMap<>(256);

	private final Map<BeanExpressionContext, StandardEvaluationContext> evaluationCache = new ConcurrentHashMap<>(8);

	private final ParserContext beanExpressionParserContext = new ParserContext() {
		@Override
		public boolean isTemplate() {
			return true;
		}
		@Override
		public String getExpressionPrefix() {
			return expressionPrefix;
		}
		@Override
		public String getExpressionSuffix() {
			return expressionSuffix;
		}
	};


	/**
	 * Create a new {@code StandardBeanExpressionResolver} with default settings.
	 */
	public StandardBeanExpressionResolver() {
		this.expressionParser = new SpelExpressionParser();
	}

	/**
	 * Create a new {@code StandardBeanExpressionResolver} with the given bean class loader,
	 * using it as the basis for expression compilation.
	 * @param beanClassLoader the factory's bean class loader
	 */
	public StandardBeanExpressionResolver(@Nullable ClassLoader beanClassLoader) {
		this.expressionParser = new SpelExpressionParser(new SpelParserConfiguration(null, beanClassLoader));
	}


	/**
	 * Set the prefix that an expression string starts with.
	 * The default is "#{".
	 * @see #DEFAULT_EXPRESSION_PREFIX
	 */
	public void setExpressionPrefix(String expressionPrefix) {
		Assert.hasText(expressionPrefix, "Expression prefix must not be empty");
		this.expressionPrefix = expressionPrefix;
	}

	/**
	 * Set the suffix that an expression string ends with.
	 * The default is "}".
	 * @see #DEFAULT_EXPRESSION_SUFFIX
	 */
	public void setExpressionSuffix(String expressionSuffix) {
		Assert.hasText(expressionSuffix, "Expression suffix must not be empty");
		this.expressionSuffix = expressionSuffix;
	}

	/**
	 * Specify the EL parser to use for expression parsing.
	 * <p>Default is a {@link org.springframework.expression.spel.standard.SpelExpressionParser},
	 * compatible with standard Unified EL style expression syntax.
	 */
	public void setExpressionParser(ExpressionParser expressionParser) {
		Assert.notNull(expressionParser, "ExpressionParser must not be null");
		this.expressionParser = expressionParser;
	}


	@Override
	@Nullable
	// TODO 解析复杂类型的value, BeanExpressionContext持有当前容器和bean的scope
	public Object evaluate(@Nullable String value, BeanExpressionContext evalContext) throws BeansException {
		if (!StringUtils.hasLength(value)) {
			return value;
		}
		try {
			// TODO 从表达式缓存中取出要分析的值value的Expression表达式, Expression是经过解析后的字符串表达式的形式表示.
			//  通过expressionInstance.getValue方法可以获取表示式的值, 也可以通过调用getValue(EvaluationContext)从评估(evaluation)
			//  上下文中获取表达式对于当前上下文的值
			Expression expr = this.expressionCache.get(value);
			if (expr == null) {
				// TODO expressionCache缓存中没有对应的Expression时, 表示之前并没有解析过这个表达式(首次解析),
				//  就需要用ExpressionParser将字符串value解析为一个Expression. 目前Spring提供了一个实现InternalSpelExpressionParser
				//  解析过程是通过parseExpression()方法来实现的, 具体实现在抽象类TemplateAwareExpressionParser中,
				//  提供了直接解析和从模版解析两种方式. 此方法有两个重载:
				//  1. parseExpression(String): 用于不需要解析占位符的情况, 直接解析字符串, 返回一个Expression
				//  2. parseExpression(String, ParserContext): 在指定的上下文环境中解析字符串, 如果上下文环境支持模板, 用模版方式解析
				//                                             否则和上面一样, 直接解析字符串, 返回一个Expression
				expr = this.expressionParser.parseExpression(value, this.beanExpressionParserContext);
				// TODO 解析后放入缓存, 以便下次直接使用
				this.expressionCache.put(value, expr);
			}
			// TODO 从缓存中取得BeanExpressionContext表达式上下文对应的StandardEvaluationContext取值上下文
			StandardEvaluationContext sec = this.evaluationCache.get(evalContext);
			if (sec == null) {
				// TODO 缓存中没有StandardEvaluationContext取值上下文时, 新建一个rootObject为evalContext的上下文
				sec = new StandardEvaluationContext(evalContext);
				// TODO 然后按顺序设置5个属性属性解析器PropertyAccessor, 这些PropertyAccessor的实现类通过实现接口中的canRead(),
				//  read(), canWrite(), write()来实现对属性的读写操作:
				//  1. ReflectivePropertyAccessor/DataBindingPropertyAccessor: 通过addPropertyAccessor()添加的默认解析器,
				//     通过反射读/写对象的属性
				//  2. BeanExpressionContextAccessor：支持从BeanExpressionContext获取属性
				//  3. BeanFactoryAccessor：支持从bean工厂获取属性
				//  4. MapAccessor：支持从map获取属性
				//  5. EnvironmentAccessor：支持从环境获取属性
				sec.addPropertyAccessor(new BeanExpressionContextAccessor());
				sec.addPropertyAccessor(new BeanFactoryAccessor());
				sec.addPropertyAccessor(new MapAccessor());
				sec.addPropertyAccessor(new EnvironmentAccessor());
				// TODO BeanFactoryResolver是针对Spring的EL bean解析器, resolve()方法调用的是beanFactory的getBean()方法
				sec.setBeanResolver(new BeanFactoryResolver(evalContext.getBeanFactory()));
				// TODO 设置标准的类型定位器(classLoad与evalContext相同), 支持java.lang下所有类型. 在类型使用时可以只使用类名或不必使用全限定名.
				sec.setTypeLocator(new StandardTypeLocator(evalContext.getBeanFactory().getBeanClassLoader()));
				// TODO 提取BeanExpressionContext中bean factory的conversionService
				ConversionService conversionService = evalContext.getBeanFactory().getConversionService();
				if (conversionService != null) {
					// TODO 如果存在, 将其包装成TypeConverter
					sec.setTypeConverter(new StandardTypeConverter(conversionService));
				}
				// TODO 勾子方法, 留给子类去实现
				customizeEvaluationContext(sec);
				this.evaluationCache.put(evalContext, sec);
			}
			// TODO 从上下文环境sec中拿出表达式expr表示的对象
			return expr.getValue(sec);
		}
		catch (Throwable ex) {
			throw new BeanExpressionException("Expression parsing failed", ex);
		}
	}

	/**
	 * Template method for customizing the expression evaluation context.
	 * <p>The default implementation is empty.
	 */
	protected void customizeEvaluationContext(StandardEvaluationContext evalContext) {
	}

}
