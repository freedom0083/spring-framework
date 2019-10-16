/*
 * Copyright 2002-2019 the original author or authors.
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

package org.springframework.expression.spel.standard;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.expression.ParseException;
import org.springframework.expression.ParserContext;
import org.springframework.expression.common.TemplateAwareExpressionParser;
import org.springframework.expression.spel.InternalParseException;
import org.springframework.expression.spel.SpelMessage;
import org.springframework.expression.spel.SpelParseException;
import org.springframework.expression.spel.SpelParserConfiguration;
import org.springframework.expression.spel.ast.Assign;
import org.springframework.expression.spel.ast.BeanReference;
import org.springframework.expression.spel.ast.BooleanLiteral;
import org.springframework.expression.spel.ast.CompoundExpression;
import org.springframework.expression.spel.ast.ConstructorReference;
import org.springframework.expression.spel.ast.Elvis;
import org.springframework.expression.spel.ast.FunctionReference;
import org.springframework.expression.spel.ast.Identifier;
import org.springframework.expression.spel.ast.Indexer;
import org.springframework.expression.spel.ast.InlineList;
import org.springframework.expression.spel.ast.InlineMap;
import org.springframework.expression.spel.ast.Literal;
import org.springframework.expression.spel.ast.MethodReference;
import org.springframework.expression.spel.ast.NullLiteral;
import org.springframework.expression.spel.ast.OpAnd;
import org.springframework.expression.spel.ast.OpDec;
import org.springframework.expression.spel.ast.OpDivide;
import org.springframework.expression.spel.ast.OpEQ;
import org.springframework.expression.spel.ast.OpGE;
import org.springframework.expression.spel.ast.OpGT;
import org.springframework.expression.spel.ast.OpInc;
import org.springframework.expression.spel.ast.OpLE;
import org.springframework.expression.spel.ast.OpLT;
import org.springframework.expression.spel.ast.OpMinus;
import org.springframework.expression.spel.ast.OpModulus;
import org.springframework.expression.spel.ast.OpMultiply;
import org.springframework.expression.spel.ast.OpNE;
import org.springframework.expression.spel.ast.OpOr;
import org.springframework.expression.spel.ast.OpPlus;
import org.springframework.expression.spel.ast.OperatorBetween;
import org.springframework.expression.spel.ast.OperatorInstanceof;
import org.springframework.expression.spel.ast.OperatorMatches;
import org.springframework.expression.spel.ast.OperatorNot;
import org.springframework.expression.spel.ast.OperatorPower;
import org.springframework.expression.spel.ast.Projection;
import org.springframework.expression.spel.ast.PropertyOrFieldReference;
import org.springframework.expression.spel.ast.QualifiedIdentifier;
import org.springframework.expression.spel.ast.Selection;
import org.springframework.expression.spel.ast.SpelNodeImpl;
import org.springframework.expression.spel.ast.StringLiteral;
import org.springframework.expression.spel.ast.Ternary;
import org.springframework.expression.spel.ast.TypeReference;
import org.springframework.expression.spel.ast.VariableReference;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Hand-written SpEL parser. Instances are reusable but are not thread-safe.
 *
 * @author Andy Clement
 * @author Juergen Hoeller
 * @author Phillip Webb
 * @since 3.0
 */
class InternalSpelExpressionParser extends TemplateAwareExpressionParser {
	// TODO \p{L}\p{N}表示匹配所有字母和数字, 类似于\w
	//  1. \p{L}: 表示匹配所有字母
	//  2. \P{L}: 表示不是任何字母, 等价于 [^\p{L}]
	//  3. \p{N}: 表示匹配所有数字, 类似于 \d 4
	//  4. \P{N}: 表示不是任何数字, 等价于 [^\p{N}]
	private static final Pattern VALID_QUALIFIED_ID_PATTERN = Pattern.compile("[\\p{L}\\p{N}_$]+");


	private final SpelParserConfiguration configuration;

	// For rules that build nodes, they are stacked here for return
	private final Deque<SpelNodeImpl> constructedNodes = new ArrayDeque<>();

	// The expression being parsed
	private String expressionString = "";

	// The token stream constructed from that expression string
	private List<Token> tokenStream = Collections.emptyList();

	// length of a populated token stream
	private int tokenStreamLength;

	// Current location in the token stream when processing tokens
	private int tokenStreamPointer;


	/**
	 * Create a parser with some configured behavior.
	 * @param configuration custom configuration options
	 */
	public InternalSpelExpressionParser(SpelParserConfiguration configuration) {
		this.configuration = configuration;
	}


	@Override

	protected SpelExpression doParseExpression(String expressionString, @Nullable ParserContext context)
			throws ParseException {

		try {
			this.expressionString = expressionString;
			// TODO 用表达式创建一个词法分析器, 其中包含了要解析的字符串表达式, 分解成char的表达式, 整个表达式长度, 以及起始位置
			Tokenizer tokenizer = new Tokenizer(expressionString);
			// TODO 用词法分析器分析传入的表达式, 会根据表达式中的操作符将对应的操作封装到token中, 返回的是一个包含所有token的集合
			this.tokenStream = tokenizer.process();
			this.tokenStreamLength = this.tokenStream.size();
			// TODO 处理token的位置, 0表示从第一个token开始
			this.tokenStreamPointer = 0;
			this.constructedNodes.clear();
			// TODO 解析表达式, 根据tokens建立语法树
			SpelNodeImpl ast = eatExpression();
			Assert.state(ast != null, "No node");
			Token t = peekToken();
			if (t != null) {
				throw new SpelParseException(t.startPos, SpelMessage.MORE_INPUT, toString(nextToken()));
			}
			Assert.isTrue(this.constructedNodes.isEmpty(), "At least one node expected");
			return new SpelExpression(expressionString, ast, this.configuration);
		}
		catch (InternalParseException ex) {
			throw ex.getCause();
		}
	}

	//	expression
	//    : logicalOrExpression
	//      ( (ASSIGN^ logicalOrExpression)
	//	    | (DEFAULT^ logicalOrExpression)
	//	    | (QMARK^ expression COLON! expression)
	//      | (ELVIS^ expression))?;
	@Nullable
	private SpelNodeImpl eatExpression() {
		// TODO 首先处理逻辑或运算符('or', 或'||')
		SpelNodeImpl expr = eatLogicalOrExpression();
		Token t = peekToken();
		if (t != null) {
			if (t.kind == TokenKind.ASSIGN) {  // a=b
				// TODO 当前操作符是'='时, 且表达式结果为空时, 给一个NullLiteral类型的node
				if (expr == null) {
					expr = new NullLiteral(t.startPos - 1, t.endPos - 1);
				}
				nextToken();
				SpelNodeImpl assignedValue = eatLogicalOrExpression();
				return new Assign(t.startPos, t.endPos, expr, assignedValue);
			}
			if (t.kind == TokenKind.ELVIS) {  // a?:b (a if it isn't null, otherwise b)
				// TODO 当前操作符是'?:'时
				if (expr == null) {
					expr = new NullLiteral(t.startPos - 1, t.endPos - 2);
				}
				nextToken();  // elvis has left the building
				SpelNodeImpl valueIfNull = eatExpression();
				if (valueIfNull == null) {
					valueIfNull = new NullLiteral(t.startPos + 1, t.endPos + 1);
				}
				return new Elvis(t.startPos, t.endPos, expr, valueIfNull);
			}
			if (t.kind == TokenKind.QMARK) {  // a?b:c
				// TODO 当前操作符是'?'的情况, 三目表达式
				if (expr == null) {
					expr = new NullLiteral(t.startPos - 1, t.endPos - 1);
				}
				nextToken();
				SpelNodeImpl ifTrueExprValue = eatExpression();
				eatToken(TokenKind.COLON);
				SpelNodeImpl ifFalseExprValue = eatExpression();
				return new Ternary(t.startPos, t.endPos, expr, ifTrueExprValue, ifFalseExprValue);
			}
		}
		return expr;
	}

	//logicalOrExpression : logicalAndExpression (OR^ logicalAndExpression)*;
	@Nullable
	private SpelNodeImpl eatLogicalOrExpression() {
		// TODO 处理或操作时, 首先处理与操作('and', 或'&&')
		SpelNodeImpl expr = eatLogicalAndExpression();
		while (peekIdentifierToken("or") || peekToken(TokenKind.SYMBOLIC_OR)) {
			Token t = takeToken();  //consume OR
			SpelNodeImpl rhExpr = eatLogicalAndExpression();
			checkOperands(t, expr, rhExpr);
			expr = new OpOr(t.startPos, t.endPos, expr, rhExpr);
		}
		return expr;
	}

	// logicalAndExpression : relationalExpression (AND^ relationalExpression)*;
	@Nullable
	private SpelNodeImpl eatLogicalAndExpression() {
		// TODO 处理与操作前, 首先处理关系运算:
		//  1. EQUAL('=='), 2. NOT_EQUAL('!='), 3. LESS_THAN('<'), 4. LESS_THAN_OR_EQUAL('<='), 5. GREATER_THAN('>')
		//  6. GREATER_THAN_OR_EQUAL('>='), 7, INSTANCEOF('instanceof'), 8. BETWEEN('between'), 9. MATCHES('matches')
		SpelNodeImpl expr = eatRelationalExpression();
		while (peekIdentifierToken("and") || peekToken(TokenKind.SYMBOLIC_AND)) {
			Token t = takeToken();  // consume 'AND'
			SpelNodeImpl rhExpr = eatRelationalExpression();
			checkOperands(t, expr, rhExpr);
			expr = new OpAnd(t.startPos, t.endPos, expr, rhExpr);
		}
		return expr;
	}

	// relationalExpression : sumExpression (relationalOperator^ sumExpression)?;
	@Nullable
	private SpelNodeImpl eatRelationalExpression() {
		// TODO 处理关系运算前, 先处理求和运算:
		//  1. PLUS('+'), 2. MINUS('-'), 3. INC('++')
		SpelNodeImpl expr = eatSumExpression();
		Token relationalOperatorToken = maybeEatRelationalOperator();
		if (relationalOperatorToken != null) {
			Token t = takeToken();  // consume relational operator token
			SpelNodeImpl rhExpr = eatSumExpression();
			checkOperands(t, expr, rhExpr);
			TokenKind tk = relationalOperatorToken.kind;

			if (relationalOperatorToken.isNumericRelationalOperator()) {
				if (tk == TokenKind.GT) {
					return new OpGT(t.startPos, t.endPos, expr, rhExpr);
				}
				if (tk == TokenKind.LT) {
					return new OpLT(t.startPos, t.endPos, expr, rhExpr);
				}
				if (tk == TokenKind.LE) {
					return new OpLE(t.startPos, t.endPos, expr, rhExpr);
				}
				if (tk == TokenKind.GE) {
					return new OpGE(t.startPos, t.endPos, expr, rhExpr);
				}
				if (tk == TokenKind.EQ) {
					return new OpEQ(t.startPos, t.endPos, expr, rhExpr);
				}
				Assert.isTrue(tk == TokenKind.NE, "Not-equals token expected");
				return new OpNE(t.startPos, t.endPos, expr, rhExpr);
			}

			if (tk == TokenKind.INSTANCEOF) {
				return new OperatorInstanceof(t.startPos, t.endPos, expr, rhExpr);
			}

			if (tk == TokenKind.MATCHES) {
				return new OperatorMatches(t.startPos, t.endPos, expr, rhExpr);
			}

			Assert.isTrue(tk == TokenKind.BETWEEN, "Between token expected");
			return new OperatorBetween(t.startPos, t.endPos, expr, rhExpr);
		}
		return expr;
	}

	//sumExpression: productExpression ( (PLUS^ | MINUS^) productExpression)*;
	@Nullable
	private SpelNodeImpl eatSumExpression() {
		// TODO 求和操作前, 要先处理乘积运算:
		//  1. START('*'), 2. DIV('/'), 3. MOD('%')
		SpelNodeImpl expr = eatProductExpression();
		while (peekToken(TokenKind.PLUS, TokenKind.MINUS, TokenKind.INC)) {
			// TODO 处理'*', '-', '++'操作符
			Token t = takeToken();  //consume PLUS or MINUS or INC
			SpelNodeImpl rhExpr = eatProductExpression();
			checkRightOperand(t, rhExpr);
			if (t.kind == TokenKind.PLUS) {
				expr = new OpPlus(t.startPos, t.endPos, expr, rhExpr);
			}
			else if (t.kind == TokenKind.MINUS) {
				expr = new OpMinus(t.startPos, t.endPos, expr, rhExpr);
			}
		}
		return expr;
	}

	// productExpression: powerExpr ((STAR^ | DIV^| MOD^) powerExpr)* ;
	@Nullable
	private SpelNodeImpl eatProductExpression() {
		// TODO 乘积操作前, 要先处理平方, 自增, 自减操作
		//  1. POWER('^'), 2. INC('++'), 3. DEC('--')
		SpelNodeImpl expr = eatPowerIncDecExpression();
		while (peekToken(TokenKind.STAR, TokenKind.DIV, TokenKind.MOD)) {
			// TODO 处理'*', '/', '%'操作符
			Token t = takeToken();  // consume STAR/DIV/MOD
			SpelNodeImpl rhExpr = eatPowerIncDecExpression();
			checkOperands(t, expr, rhExpr);
			if (t.kind == TokenKind.STAR) {
				expr = new OpMultiply(t.startPos, t.endPos, expr, rhExpr);
			}
			else if (t.kind == TokenKind.DIV) {
				expr = new OpDivide(t.startPos, t.endPos, expr, rhExpr);
			}
			else {
				Assert.isTrue(t.kind == TokenKind.MOD, "Mod token expected");
				expr = new OpModulus(t.startPos, t.endPos, expr, rhExpr);
			}
		}
		return expr;
	}

	// powerExpr  : unaryExpression (POWER^ unaryExpression)? (INC || DEC) ;
	@Nullable
	private SpelNodeImpl eatPowerIncDecExpression() {
		// TODO 处理平方, 自增, 自减操作前, 先处理一元操作符:
		//  1. PLUS('+'), 2. MINUS('-'), 3. NOT('!'), 4. INC('++'), 5. DEC('--')
		SpelNodeImpl expr = eatUnaryExpression();
		if (peekToken(TokenKind.POWER)) {
			// TODO 当前token是'^'操作符时, 消费掉'^'操作符
			Token t = takeToken();  //consume POWER
			SpelNodeImpl rhExpr = eatUnaryExpression();
			checkRightOperand(t, rhExpr);
			return new OperatorPower(t.startPos, t.endPos, expr, rhExpr);
		}
		if (expr != null && peekToken(TokenKind.INC, TokenKind.DEC)) {
			// TODO 处理自增和自减的情况
			Token t = takeToken();  //consume INC/DEC
			if (t.getKind() == TokenKind.INC) {
				return new OpInc(t.startPos, t.endPos, true, expr);
			}
			return new OpDec(t.startPos, t.endPos, true, expr);
		}
		return expr;
	}

	// unaryExpression: (PLUS^ | MINUS^ | BANG^ | INC^ | DEC^) unaryExpression | primaryExpression ;
	@Nullable
	// TODO 处理一元表达式:
	//  PLUS -> '+', MINUS -> '-', NOT -> '!', INC -> '++', DEC -> '--'
	private SpelNodeImpl eatUnaryExpression() {
		if (peekToken(TokenKind.PLUS, TokenKind.MINUS, TokenKind.NOT)) {
			// TODO 当前token是'+', '-', '!'操作符时, 拿出对应的操作符token, 同时token集合的指向后一个token
			Token t = takeToken();
			// TODO 然后递归后面的token
			SpelNodeImpl expr = eatUnaryExpression();
			Assert.state(expr != null, "No node");
			if (t.kind == TokenKind.NOT) {
				return new OperatorNot(t.startPos, t.endPos, expr);
			}
			if (t.kind == TokenKind.PLUS) {
				return new OpPlus(t.startPos, t.endPos, expr);
			}
			Assert.isTrue(t.kind == TokenKind.MINUS, "Minus token expected");
			return new OpMinus(t.startPos, t.endPos, expr);
		}
		if (peekToken(TokenKind.INC, TokenKind.DEC)) {
			// TODO 当前token是'++', '--'时, 拿出对应的操作符token, 同时token集合的指针指向后一个token
			Token t = takeToken();
			// TODO 然后递归后面的token
			SpelNodeImpl expr = eatUnaryExpression();
			if (t.getKind() == TokenKind.INC) {
				return new OpInc(t.startPos, t.endPos, false, expr);
			}
			return new OpDec(t.startPos, t.endPos, false, expr);
		}
		// TODO 其他情况时, 应该是个表达式, 而非操作符, 即: 操作符要操作的主体token
		return eatPrimaryExpression();
	}

	// primaryExpression : startNode (node)? -> ^(EXPRESSION startNode (node)?);
	@Nullable
	private SpelNodeImpl eatPrimaryExpression() {
		// TODO 走到这时, 就表示当前的token不是一个运算符(可能是个字面量, 引用, '('等等), 开始解析当前token
		SpelNodeImpl start = eatStartNode();  // always a start node
		List<SpelNodeImpl> nodes = null;
		// TODO 然后解析下一个token, 这时会有两种情况:这个token也许还解析其他表达式, 只要其后包含有'.[', '[', 因为SpEL支持方法调用表达式, 所以下一个token有可能会是'.', 或'?.', 也有可能不是:
		//  1. 包含'.', 或'?.': 表示其为一个方法, 属性, 函数, 变量, 投影, 或选择操作. 会生成对应的语法node, 也许还会解析其中包含的表达式.
		//  2. 不包含'.', 或'?.': 其有可能是个索引, 也有可能是个表达式
		SpelNodeImpl node = eatNode();
		while (node != null) {
			// TODO 如果生成了对应的语法node, 将其加入到集合中, 再继续向后解析, 直到解析结果为空(token不带'.', 也不是'[')
			if (nodes == null) {
				nodes = new ArrayList<>(4);
				nodes.add(start);
			}
			nodes.add(node);
			node = eatNode();
		}
		if (start == null || nodes == null) {
			return start;
		}
		// TODO
		return new CompoundExpression(start.getStartPosition(), nodes.get(nodes.size() - 1).getEndPosition(),
				nodes.toArray(new SpelNodeImpl[0]));
	}

	// node : ((DOT dottedNode) | (SAFE_NAVI dottedNode) | nonDottedNode)+;
	@Nullable
	private SpelNodeImpl eatNode() {
		// TODO 处理带'.', 或不带'.'的情况
		return (peekToken(TokenKind.DOT, TokenKind.SAFE_NAVI) ? eatDottedNode() : eatNonDottedNode());
	}

	// nonDottedNode: indexer;
	@Nullable
	private SpelNodeImpl eatNonDottedNode() {
		if (peekToken(TokenKind.LSQUARE)) {
			// TODO 没有'.'但是有'['时, 有可能就是个索引, 要么就是个表达式.
			if (maybeEatIndexer()) {
				return pop();
			}
		}
		return null;
	}

	//dottedNode
	// : ((methodOrProperty
	//	  | functionOrVar
	//    | projection
	//    | selection
	//    | firstSelection
	//    | lastSelection
	//    ))
	//	;
	private SpelNodeImpl eatDottedNode() {
		Token t = takeToken();  // it was a '.' or a '?.'
		// TODO SpEL里除了'.'表示调用方法的属性或方法外, 还可以使用'?.'来执行类似三目运算符的功能, 比如:
		//  #{person.getName?.toUpperCase()}, 表示当getName()有值时转为大写字母, 否则不执行后续方法
		boolean nullSafeNavigation = (t.kind == TokenKind.SAFE_NAVI);
		// TODO 当遇到'.'时, 后面的token有可能是下面几种情况:这里进行了4个判断
		//  1. 被调用的方法或属性:
		//  2. 函数或变量:
		//  3. 投影运算符('.![]'): 从集合的每个成员中选择特定的属性放到另外一个集合中, 类似Stream的map():
		//                        #{person.order.items.![name]}, 表示把order中的每个item的名字投影到一个String类型的集合中
		//  4. 选择运算符: 对集合进行过滤, 得到一个子集, 类似Stream的filter(), 一共有3个选择运算符:
		//               1. '.?[expression]': 提取与表达式完全匹配的元素放入集合. #{person.order.items.?[name eq 'rice']},
		//                                    表示取得order中所有名字是'rice'的item
		//               2. '.^[expression]': 提取与表达式完全匹配的第一个元素放入集合. #{person.order.items.^[name eq 'rice']},
		//                                    表示取得order中第一个名为'rice'的item
		//               3. '.$[expression]': 提取与表达式完全匹配的最后一个元素放入集合. #{person.order.items.$[name eq 'rice']},
		//                                    表示取得order中最后一个名为'rice'的item
		if (maybeEatMethodOrProperty(nullSafeNavigation) || maybeEatFunctionOrVar() ||
				maybeEatProjection(nullSafeNavigation) || maybeEatSelection(nullSafeNavigation)) {
			// TODO 进到这里时, 已经处理了'.'后的token了, 这里弹出的是刚刚处理的node
			return pop();
		}
		if (peekToken() == null) {
			// unexpectedly ran out of data
			throw internalException(t.startPos, SpelMessage.OOD);
		}
		else {
			throw internalException(t.startPos, SpelMessage.UNEXPECTED_DATA_AFTER_DOT, toString(peekToken()));
		}
	}

	// functionOrVar
	// : (POUND ID LPAREN) => function
	// | var
	//
	// function : POUND id=ID methodArgs -> ^(FUNCTIONREF[$id] methodArgs);
	// var : POUND id=ID -> ^(VARIABLEREF[$id]);
	private boolean maybeEatFunctionOrVar() {
		// TODO 函数或变量都是以'#'开头的, 检查一下当前token是否符合要求
		if (!peekToken(TokenKind.HASH)) {
			return false;
		}
		// TODO 拿出操作符'#'
		Token t = takeToken();
		// TODO 拿出'#'后的token(有可能是个function, 也有可能是个var)
		Token functionOrVariableName = eatToken(TokenKind.IDENTIFIER);
		// TODO 拿出可能存在的参数
		SpelNodeImpl[] args = maybeEatMethodArgs();
		if (args == null) {
			// TODO 没有参数时, 表示其为一个var, 创建一个表示变量的VariableReference类型node入队
			push(new VariableReference(functionOrVariableName.stringValue(),
					t.startPos, functionOrVariableName.endPos));
			return true;
		}
		// TODO 有参数时创建一个表示方法的FunctionReference型node入队
		push(new FunctionReference(functionOrVariableName.stringValue(),
				t.startPos, functionOrVariableName.endPos, args));
		return true;
	}

	// methodArgs : LPAREN! (argument (COMMA! argument)* (COMMA!)?)? RPAREN!;
	@Nullable
	private SpelNodeImpl[] maybeEatMethodArgs() {
		// TODO 解析方法的参数, 所以要先判断一下当前token是否为'('
		if (!peekToken(TokenKind.LPAREN)) {
			return null;
		}
		List<SpelNodeImpl> args = new ArrayList<>();
		consumeArguments(args);
		// TODO 跳过')'后, 将解析好的参数放到列表中返回
		eatToken(TokenKind.RPAREN);
		return args.toArray(new SpelNodeImpl[0]);
	}

	private void eatConstructorArgs(List<SpelNodeImpl> accumulatedArguments) {
		// TODO 解析构造器的参数, 与解析方法参数类似
		if (!peekToken(TokenKind.LPAREN)) {
			throw new InternalParseException(new SpelParseException(this.expressionString,
					positionOf(peekToken()), SpelMessage.MISSING_CONSTRUCTOR_ARGS));
		}
		consumeArguments(accumulatedArguments);
		eatToken(TokenKind.RPAREN);
	}

	/**
	 * Used for consuming arguments for either a method or a constructor call.
	 */
	private void consumeArguments(List<SpelNodeImpl> accumulatedArguments) {
		Token t = peekToken();
		Assert.state(t != null, "Expected token");
		int pos = t.startPos;
		Token next;
		do {
			nextToken();  // consume (first time through) or comma (subsequent times)
			t = peekToken();
			if (t == null) {
				throw internalException(pos, SpelMessage.RUN_OUT_OF_ARGUMENTS);
			}
			if (t.kind != TokenKind.RPAREN) {
				// TODO token不是'('时, 表示当前为一个参数, 对其进行解析, 把结果入到参数node集合里
				accumulatedArguments.add(eatExpression());
			}
			next = peekToken();
		}
		// TODO 整个解析过程的退出条件是: 后续不再有token, 或者后续token不是',', 表示由','分割的参数全部解析完成了
		while (next != null && next.kind == TokenKind.COMMA);

		if (next == null) {
			throw internalException(pos, SpelMessage.RUN_OUT_OF_ARGUMENTS);
		}
	}

	private int positionOf(@Nullable Token t) {
		if (t == null) {
			// if null assume the problem is because the right token was
			// not found at the end of the expression
			return this.expressionString.length();
		}
		return t.startPos;
	}

	//startNode
	// : parenExpr | literal
	//	    | type
	//	    | methodOrProperty
	//	    | functionOrVar
	//	    | projection
	//	    | selection
	//	    | firstSelection
	//	    | lastSelection
	//	    | indexer
	//	    | constructor
	@Nullable
	private SpelNodeImpl eatStartNode() {
		if (maybeEatLiteral()) {
			// TODO token是字面量时, 弹出解析后的node准备下一步计算
			return pop();
		}
		else if (maybeEatParenExpression()) {
			// TODO token就左括号时, 弹出解析后的node准备下一步计算
			return pop();
		}
		else if (maybeEatTypeReference() || maybeEatNullReference() || maybeEatConstructorReference() ||
				maybeEatMethodOrProperty(false) || maybeEatFunctionOrVar()) {
			// TODO token是类型引用, 空引用, 构造引用, 方法或属性, 以及函数类型时, 弹出解析后的node准备下一步计算
			return pop();
		}
		else if (maybeEatBeanReference()) {
			// TODO token是bean引用时(以'@', 或'&'开头), 弹出解析后的node
			return pop();
		}
		else if (maybeEatProjection(false) || maybeEatSelection(false) || maybeEatIndexer()) {
			return pop();
		}
		else if (maybeEatInlineListOrMap()) {
			return pop();
		}
		else {
			return null;
		}
	}

	// parse: @beanname @'bean.name'
	// quoted if dotted
	private boolean maybeEatBeanReference() {
		if (peekToken(TokenKind.BEAN_REF) || peekToken(TokenKind.FACTORY_BEAN_REF)) {
			// TODO 以'@', 或'&'开头表示表达式是一个bean引用
			Token beanRefToken = takeToken();
			Token beanNameToken = null;
			String beanName = null;
			if (peekToken(TokenKind.IDENTIFIER)) {
				beanNameToken = eatToken(TokenKind.IDENTIFIER);
				beanName = beanNameToken.stringValue();
			}
			else if (peekToken(TokenKind.LITERAL_STRING)) {
				beanNameToken = eatToken(TokenKind.LITERAL_STRING);
				beanName = beanNameToken.stringValue();
				beanName = beanName.substring(1, beanName.length() - 1);
			}
			else {
				throw internalException(beanRefToken.startPos, SpelMessage.INVALID_BEAN_REFERENCE);
			}
			BeanReference beanReference;
			if (beanRefToken.getKind() == TokenKind.FACTORY_BEAN_REF) {
				String beanNameString = String.valueOf(TokenKind.FACTORY_BEAN_REF.tokenChars) + beanName;
				beanReference = new BeanReference(beanRefToken.startPos, beanNameToken.endPos, beanNameString);
			}
			else {
				beanReference = new BeanReference(beanNameToken.startPos, beanNameToken.endPos, beanName);
			}
			this.constructedNodes.push(beanReference);
			return true;
		}
		return false;
	}

	private boolean maybeEatTypeReference() {
		if (peekToken(TokenKind.IDENTIFIER)) {
			// TODO 如果是IDENTIFIER类型的token, 再拿出来看看是不是类型引用的token(值是'T')
			Token typeName = peekToken();
			Assert.state(typeName != null, "Expected token");
			if (!"T".equals(typeName.stringValue())) {
				return false;
			}
			// It looks like a type reference but is T being used as a map key?
			// TODO 把当前的token(即'T')拿出来, 然后指向下一个token
			Token t = takeToken();
			if (peekToken(TokenKind.RSQUARE)) {
				// looks like 'T]' (T is map key)
				// TODO 如果当前token('T'的下一个token)是']'('T]'), 表示T是一个map的key, 生成一个PropertyOrFieldReference类型
				//  的语法node并放入队列, 准备进行下一步计算
				push(new PropertyOrFieldReference(false, t.stringValue(), t.startPos, t.endPos));
				return true;
			}
			// TODO 走到这时, 表示的是'T'后面指定了类型, 如: 'T[java.lang.String]', 这个时候通过eatToken()取出当前token(这里并没有用到返回值)
			//  并将指针指向下一个token, 相当于跳过'左方括号('[')'
			eatToken(TokenKind.LPAREN);
			// TODO 解析全限定名, java.lang.String会按'.'进行切分, 最终返回一个QualifiedIdentifier类型的语法node
			SpelNodeImpl node = eatPossiblyQualifiedId();
			// dotted qualified id
			// Are there array dimensions?
			int dims = 0;
			// TODO 这里是处理数组类型的情况, 和上面流程一样
			while (peekToken(TokenKind.LSQUARE, true)) {
				eatToken(TokenKind.RSQUARE);
				dims++;
			}
			// TODO 处理完类型后, 跳过']', 将指针指向下一个token
			eatToken(TokenKind.RPAREN);
			// TODO 最后生成一个包含解析后全限定名QualifiedIdentifier的TypeReference类型的语法node放入队列准备后续计算
			this.constructedNodes.push(new TypeReference(typeName.startPos, typeName.endPos, node, dims));
			return true;
		}
		return false;
	}

	private boolean maybeEatNullReference() {
		if (peekToken(TokenKind.IDENTIFIER)) {
			Token nullToken = peekToken();
			Assert.state(nullToken != null, "Expected token");
			if (!"null".equalsIgnoreCase(nullToken.stringValue())) {
				return false;
			}
			nextToken();
			this.constructedNodes.push(new NullLiteral(nullToken.startPos, nullToken.endPos));
			return true;
		}
		return false;
	}

	//projection: PROJECT^ expression RCURLY!;
	private boolean maybeEatProjection(boolean nullSafeNavigation) {
		Token t = peekToken();
		if (!peekToken(TokenKind.PROJECT, true)) {
			return false;
		}
		Assert.state(t != null, "No token");
		// TODO 投影操作接受一个表达式, 所以先解析其中的表达式
		SpelNodeImpl expr = eatExpression();
		Assert.state(expr != null, "No node");
		eatToken(TokenKind.RSQUARE);
		// TODO 解析完成后, 生成一个Projection的node放入队列
		this.constructedNodes.push(new Projection(nullSafeNavigation, t.startPos, t.endPos, expr));
		return true;
	}

	// list = LCURLY (element (COMMA element)*) RCURLY
	// map  = LCURLY (key ':' value (COMMA key ':' value)*) RCURLY
	private boolean maybeEatInlineListOrMap() {
		Token t = peekToken();
		// TODO 如果token是'{', 则指向下一个token. 如果不是表示并非map或list, 返回false
		if (!peekToken(TokenKind.LCURLY, true)) {
			return false;
		}
		Assert.state(t != null, "No token");
		SpelNodeImpl expr = null;
		// TODO 拿出'{'的下一个token
		Token closingCurly = peekToken();
		if (peekToken(TokenKind.RCURLY, true)) {
			// empty list '{}'
			// TODO 如果是一个'}', 表示为一个空的list, '{}', 返回一个空的InlineList类型的node
			Assert.state(closingCurly != null, "No token");
			expr = new InlineList(t.startPos, closingCurly.endPos);
		}
		else if (peekToken(TokenKind.COLON, true)) {
			// TODO 如果是':', 表示为一个map, 取得':'后的value值, 如果这个值是'}', 则表示为一个空map, 返回一个空的InlineMap类型的node
			closingCurly = eatToken(TokenKind.RCURLY);
			// empty map '{:}'
			expr = new InlineMap(t.startPos, closingCurly.endPos);
		}
		else {
			// TODO 否则就是有值的情况, 因为值也可能是个表达式, 所以使用eatExpression()进行解析
			SpelNodeImpl firstExpression = eatExpression();
			// Next is either:
			// '}' - end of list
			// ',' - more expressions in this list
			// ':' - this is a map!
			if (peekToken(TokenKind.RCURLY)) {  // list with one item in it
				// TODO 后面的token是'}', 表示集合处理完了, 用集合的值生成一个InlineList类型的node
				List<SpelNodeImpl> elements = new ArrayList<>();
				elements.add(firstExpression);
				closingCurly = eatToken(TokenKind.RCURLY);
				expr = new InlineList(t.startPos, closingCurly.endPos, elements.toArray(new SpelNodeImpl[0]));
			}
			else if (peekToken(TokenKind.COMMA, true)) {  // multi-item list
				// TODO 如果后面是',', 表示集合内还有元素待解析, 通过循环继续解析, 直到全部解析完毕, 然后生成一个InlineList类型的node
				List<SpelNodeImpl> elements = new ArrayList<>();
				elements.add(firstExpression);
				do {
					elements.add(eatExpression());
				}
				while (peekToken(TokenKind.COMMA, true));
				closingCurly = eatToken(TokenKind.RCURLY);
				expr = new InlineList(t.startPos, closingCurly.endPos, elements.toArray(new SpelNodeImpl[0]));

			}
			else if (peekToken(TokenKind.COLON, true)) {  // map!
				// TODO 如果后面是':', 表示其为一个map, 然后将key-value按顺序放入集合, 其中每组KV对用','分割, 然后用这个集合生成一个InlineMap类型的node
				List<SpelNodeImpl> elements = new ArrayList<>();
				elements.add(firstExpression);
				elements.add(eatExpression());
				while (peekToken(TokenKind.COMMA, true)) {
					elements.add(eatExpression());
					eatToken(TokenKind.COLON);
					elements.add(eatExpression());
				}
				closingCurly = eatToken(TokenKind.RCURLY);
				expr = new InlineMap(t.startPos, closingCurly.endPos, elements.toArray(new SpelNodeImpl[0]));
			}
			else {
				throw internalException(t.startPos, SpelMessage.OOD);
			}
		}
		// TODO 将node放入队列, 准备下一步操作
		this.constructedNodes.push(expr);
		return true;
	}

	private boolean maybeEatIndexer() {
		Token t = peekToken();
		if (!peekToken(TokenKind.LSQUARE, true)) {
			return false;
		}
		Assert.state(t != null, "No token");
		// TODO '[]'内有可能是个索引, 也有可能是个表达式, 对其进行解析, 然后用解析结果生成一个Indexer类型的node放入队列
		SpelNodeImpl expr = eatExpression();
		Assert.state(expr != null, "No node");
		eatToken(TokenKind.RSQUARE);
		this.constructedNodes.push(new Indexer(t.startPos, t.endPos, expr));
		return true;
	}

	private boolean maybeEatSelection(boolean nullSafeNavigation) {
		Token t = peekToken();
		if (!peekSelectToken()) {
			return false;
		}
		Assert.state(t != null, "No token");
		nextToken();
		// TODO 选择操作接受一个表达式, 所以先解析其中的表达式
		SpelNodeImpl expr = eatExpression();
		if (expr == null) {
			throw internalException(t.startPos, SpelMessage.MISSING_SELECTION_EXPRESSION);
		}
		eatToken(TokenKind.RSQUARE);
		// TODO 然后根据选择操作符, 生成对应的node放入队列
		if (t.kind == TokenKind.SELECT_FIRST) {
			this.constructedNodes.push(new Selection(nullSafeNavigation, Selection.FIRST, t.startPos, t.endPos, expr));
		}
		else if (t.kind == TokenKind.SELECT_LAST) {
			this.constructedNodes.push(new Selection(nullSafeNavigation, Selection.LAST, t.startPos, t.endPos, expr));
		}
		else {
			this.constructedNodes.push(new Selection(nullSafeNavigation, Selection.ALL, t.startPos, t.endPos, expr));
		}
		return true;
	}

	/**
	 * Eat an identifier, possibly qualified (meaning that it is dotted).
	 * TODO AndyC Could create complete identifiers (a.b.c) here rather than a sequence of them? (a, b, c)
	 */
	private SpelNodeImpl eatPossiblyQualifiedId() {
		Deque<SpelNodeImpl> qualifiedIdPieces = new ArrayDeque<>();
		Token node = peekToken();
		// TODO 测试一下token, 如果是DOT('.'), IDENTIFIER, 或字母和数字时, 即是否为一个限定名, 比如: java.lang.String
		//  这个循环是用来把全限定名按'.'进行分解, 生成Idetifier类型的node, 并按顺序加入到限定名集合中
		while (isValidQualifiedId(node)) {
			// TODO 当前token是DOT('.'), IDENTIFIER, 或字母和数字时, 指向下一个token
			nextToken();
			if (node.kind != TokenKind.DOT) {
				// TODO 全限定名按'.'进行分解, 生成Idetifier类型的node, 并按顺序加入到限定名集合中
				qualifiedIdPieces.add(new Identifier(node.stringValue(), node.startPos, node.endPos));
			}
			// TODO 前面已经执行过nextToken()了, 这里就是测试下一个node, 直到遇到非DOT, IDENTIFIER, 字母或数字外的其他token
			node = peekToken();
		}

		if (qualifiedIdPieces.isEmpty()) {
			if (node == null) {
				throw internalException( this.expressionString.length(), SpelMessage.OOD);
			}
			throw internalException(node.startPos, SpelMessage.NOT_EXPECTED_TOKEN,
					"qualified ID", node.getKind().toString().toLowerCase());
		}
		// TODO 根据队列生成一个QualifiedIdentifier类型的node, 这个node与全限定名进行了关联, node包含有全限定名数组,
		//  全限定名中的每个node的父节点都指定这个node
		return new QualifiedIdentifier(qualifiedIdPieces.getFirst().getStartPosition(),
				qualifiedIdPieces.getLast().getEndPosition(), qualifiedIdPieces.toArray(new SpelNodeImpl[0]));
	}

	private boolean isValidQualifiedId(@Nullable Token node) {
		if (node == null || node.kind == TokenKind.LITERAL_STRING) {
			return false;
		}
		if (node.kind == TokenKind.DOT || node.kind == TokenKind.IDENTIFIER) {
			return true;
		}
		String value = node.stringValue();
		// TODO 判断node是否为字母或数字
		return (StringUtils.hasLength(value) && VALID_QUALIFIED_ID_PATTERN.matcher(value).matches());
	}

	// This is complicated due to the support for dollars in identifiers.
	// Dollars are normally separate tokens but there we want to combine
	// a series of identifiers and dollars into a single identifier.
	private boolean maybeEatMethodOrProperty(boolean nullSafeNavigation) {
		if (peekToken(TokenKind.IDENTIFIER)) {
			// TODO 只要当前操作的token是IDENTIFIER类型, 都表示其为一个方法或属性
			Token methodOrPropertyName = takeToken();
			// TODO 拿出随后的方法参数
			SpelNodeImpl[] args = maybeEatMethodArgs();
			if (args == null) {
				// property
				// TODO 没参数表示其为一个属性, 创建一个PropertyOrFieldReference类型的node放入队列
				push(new PropertyOrFieldReference(nullSafeNavigation, methodOrPropertyName.stringValue(),
						methodOrPropertyName.startPos, methodOrPropertyName.endPos));
				return true;
			}
			// method reference
			// TODO 有参数表示其为一个方法引用, 创建一个MethodReference类型的node放入队列
			push(new MethodReference(nullSafeNavigation, methodOrPropertyName.stringValue(),
					methodOrPropertyName.startPos, methodOrPropertyName.endPos, args));
			// TODO what is the end position for a method reference? the name or the last arg?
			return true;
		}
		return false;
	}

	//constructor
    //:	('new' qualifiedId LPAREN) => 'new' qualifiedId ctorArgs -> ^(CONSTRUCTOR qualifiedId ctorArgs)
	private boolean maybeEatConstructorReference() {
		if (peekIdentifierToken("new")) {
			// TODO 构造引用就是看token是否为'new'
			Token newToken = takeToken();
			// It looks like a constructor reference but is NEW being used as a map key?
			if (peekToken(TokenKind.RSQUARE)) {
				// TODO 如果'new'后跟的是一个']', 则表示为一个map的key, 这时构建一个PropertyOrFieldReference类型的语法node放入队列
				// looks like 'NEW]' (so NEW used as map key)
				push(new PropertyOrFieldReference(false, newToken.stringValue(), newToken.startPos, newToken.endPos));
				return true;
			}
			// TODO 取出new后面的token, 解析出表示全限定名的node放入集合
			SpelNodeImpl possiblyQualifiedConstructorName = eatPossiblyQualifiedId();
			List<SpelNodeImpl> nodes = new ArrayList<>();

			nodes.add(possiblyQualifiedConstructorName);

			if (peekToken(TokenKind.LSQUARE)) {
				// array initializer
				// TODO 如果全限定名后出现了'[', 则表示是一个数组, 这时会把数组内所有元素解析成node加入到元素集合中, 为了后面设置node引用
				List<SpelNodeImpl> dimensions = new ArrayList<>();
				// TODO 测试一下token是否为'[', 如果是, token集合指针向后移动一位. 这个while是为了解析多元数组的情况
				while (peekToken(TokenKind.LSQUARE, true)) {
					if (!peekToken(TokenKind.RSQUARE)) {
						// TODO token不是']'时, 处理中间的token(有可能是个表达式)
						dimensions.add(eatExpression());
					}
					else {
						// TODO 遇到']'时放入一个null
						dimensions.add(null);
					}
					// TODO 跳过']'
					eatToken(TokenKind.RSQUARE);
				}
				// TODO 处理内嵌集合或map的情况, 比如'[]{}'
				if (maybeEatInlineListOrMap()) {
					nodes.add(pop());
				}
				// TODO 最后生成一个表示构造引用的node, 然后入队
				push(new ConstructorReference(newToken.startPos, newToken.endPos,
						dimensions.toArray(new SpelNodeImpl[0]), nodes.toArray(new SpelNodeImpl[0])));
			}
			else {
				// regular constructor invocation
				// TODO 全限定名后没有'['时, 直接解析全限定名, 然后将生成的表示构造引用的node加入到队列
				eatConstructorArgs(nodes);
				// TODO correct end position?
				push(new ConstructorReference(newToken.startPos, newToken.endPos, nodes.toArray(new SpelNodeImpl[0])));
			}
			return true;
		}
		return false;
	}

	private void push(SpelNodeImpl newNode) {
		this.constructedNodes.push(newNode);
	}

	private SpelNodeImpl pop() {
		return this.constructedNodes.pop();
	}

	//	literal
	//  : INTEGER_LITERAL
	//	| boolLiteral
	//	| STRING_LITERAL
	//  | HEXADECIMAL_INTEGER_LITERAL
	//  | REAL_LITERAL
	//	| DQ_STRING_LITERAL
	//	| NULL_LITERAL
	private boolean maybeEatLiteral() {
		Token t = peekToken();
		if (t == null) {
			return false;
		}
		if (t.kind == TokenKind.LITERAL_INT) {
			// TODO 返回int型字面值的SpelNode放入队列
			push(Literal.getIntLiteral(t.stringValue(), t.startPos, t.endPos, 10));
		}
		else if (t.kind == TokenKind.LITERAL_LONG) {
			// TODO 返回long型字面值的SpelNode放入队列
			push(Literal.getLongLiteral(t.stringValue(), t.startPos, t.endPos, 10));
		}
		else if (t.kind == TokenKind.LITERAL_HEXINT) {
			// TODO 返回int型字面值的SpelNode放入队列
			push(Literal.getIntLiteral(t.stringValue(), t.startPos, t.endPos, 16));
		}
		else if (t.kind == TokenKind.LITERAL_HEXLONG) {
			// TODO 返回long型字面值的SpelNode放入队列
			push(Literal.getLongLiteral(t.stringValue(), t.startPos, t.endPos, 16));
		}
		else if (t.kind == TokenKind.LITERAL_REAL) {
			// TODO 返回double型字面值(isFloat为flase)的SpelNode放入队列
			push(Literal.getRealLiteral(t.stringValue(), t.startPos, t.endPos, false));
		}
		else if (t.kind == TokenKind.LITERAL_REAL_FLOAT) {
			// TODO 返回float型字面值(isFloat为true)的SpelNode放入队列
			push(Literal.getRealLiteral(t.stringValue(), t.startPos, t.endPos, true));
		}
		else if (peekIdentifierToken("true")) {
			// TODO 返回boolean型字面值的SpelNode放入队列
			push(new BooleanLiteral(t.stringValue(), t.startPos, t.endPos, true));
		}
		else if (peekIdentifierToken("false")) {
			// TODO 返回boolean型字面值的SpelNode放入队列
			push(new BooleanLiteral(t.stringValue(), t.startPos, t.endPos, false));
		}
		else if (t.kind == TokenKind.LITERAL_STRING) {
			// TODO 返回String型字面值的SpelNode放入队列
			push(new StringLiteral(t.stringValue(), t.startPos, t.endPos, t.stringValue()));
		}
		else {
			// TODO 非字面量的情况
			return false;
		}
		// TODO 移动到下一个token
		nextToken();
		return true;
	}

	//parenExpr : LPAREN! expression RPAREN!;
	private boolean maybeEatParenExpression() {
		if (peekToken(TokenKind.LPAREN)) {
			// TODO 解析括号, 如果存在左括号, 定位到下一个token
			nextToken();
			// TODO 括号内有可能也是表达式, 继续解析括号内的token
			SpelNodeImpl expr = eatExpression();
			Assert.state(expr != null, "No node");
			// TODO 然后跳过右括号
			eatToken(TokenKind.RPAREN);
			// TODO 把处理结果放入队列, 准备后续处理
			push(expr);
			return true;
		}
		else {
			return false;
		}
	}

	// relationalOperator
	// : EQUAL | NOT_EQUAL | LESS_THAN | LESS_THAN_OR_EQUAL | GREATER_THAN
	// | GREATER_THAN_OR_EQUAL | INSTANCEOF | BETWEEN | MATCHES
	@Nullable
	private Token maybeEatRelationalOperator() {
		Token t = peekToken();
		if (t == null) {
			return null;
		}
		if (t.isNumericRelationalOperator()) {
			return t;
		}
		if (t.isIdentifier()) {
			String idString = t.stringValue();
			if (idString.equalsIgnoreCase("instanceof")) {
				return t.asInstanceOfToken();
			}
			if (idString.equalsIgnoreCase("matches")) {
				return t.asMatchesToken();
			}
			if (idString.equalsIgnoreCase("between")) {
				return t.asBetweenToken();
			}
		}
		return null;
	}

	private Token eatToken(TokenKind expectedKind) {
		// TODO 取出当前token, 并将集合指针指向队列中下一个token
		Token t = nextToken();
		if (t == null) {
			int pos = this.expressionString.length();
			throw internalException(pos, SpelMessage.OOD);
		}
		if (t.kind != expectedKind) {
			throw internalException(t.startPos, SpelMessage.NOT_EXPECTED_TOKEN,
					expectedKind.toString().toLowerCase(), t.getKind().toString().toLowerCase());
		}
		// TODO 返回当前token
		return t;
	}

	private boolean peekToken(TokenKind desiredTokenKind) {
		return peekToken(desiredTokenKind, false);
	}

	private boolean peekToken(TokenKind desiredTokenKind, boolean consumeIfMatched) {
		Token t = peekToken();
		if (t == null) {
			return false;
		}
		if (t.kind == desiredTokenKind) {
			// TODO 只要token的类型和期待类型相同, 就返回true
			if (consumeIfMatched) {
				// TODO 用于控制当前token是否被消费, 即, 单纯用于测试, 还是测试成功后取值:
				//  1. true: 表示取得token用于后续操作, 所以tokenStreamPointer执行自增操作指向下一个token的位置
				//  2. false: 只用于测试, 所以不需要自增
				this.tokenStreamPointer++;
			}
			return true;
		}

		if (desiredTokenKind == TokenKind.IDENTIFIER) {
			// Might be one of the textual forms of the operators (e.g. NE for != ) -
			// in which case we can treat it as an identifier. The list is represented here:
			// Tokenizer.alternativeOperatorNames and those ones are in order in the TokenKind enum.
			// TODO token是IDENTIFIER的情况, 判断一下其是否为可替换的逻辑操作符:
			//  DIV -> '/', EQ -> '==', GE -> '>=', GT -> '>', LE -> '<=', LT -> '<', MOD -> '%', NE -> '!=', NOT -> '!'
			if (t.kind.ordinal() >= TokenKind.DIV.ordinal() && t.kind.ordinal() <= TokenKind.NOT.ordinal() &&
					t.data != null) {
				// if t.data were null, we'd know it wasn't the textual form, it was the symbol form
				return true;
			}
		}
		return false;
	}

	private boolean peekToken(TokenKind possible1, TokenKind possible2) {
		Token t = peekToken();
		if (t == null) {
			return false;
		}
		return (t.kind == possible1 || t.kind == possible2);
	}

	private boolean peekToken(TokenKind possible1, TokenKind possible2, TokenKind possible3) {
		Token t = peekToken();
		if (t == null) {
			return false;
		}
		return (t.kind == possible1 || t.kind == possible2 || t.kind == possible3);
	}

	private boolean peekIdentifierToken(String identifierString) {
		Token t = peekToken();
		if (t == null) {
			return false;
		}
		return (t.kind == TokenKind.IDENTIFIER && identifierString.equalsIgnoreCase(t.stringValue()));
	}

	private boolean peekSelectToken() {
		Token t = peekToken();
		if (t == null) {
			return false;
		}
		return (t.kind == TokenKind.SELECT || t.kind == TokenKind.SELECT_FIRST || t.kind == TokenKind.SELECT_LAST);
	}

	private Token takeToken() {
		if (this.tokenStreamPointer >= this.tokenStreamLength) {
			throw new IllegalStateException("No token");
		}
		// TODO 返回当前要操作的token
		return this.tokenStream.get(this.tokenStreamPointer++);
	}

	@Nullable
	private Token nextToken() {
		if (this.tokenStreamPointer >= this.tokenStreamLength) {
			return null;
		}
		return this.tokenStream.get(this.tokenStreamPointer++);
	}

	@Nullable
	// TODO 用于测试当前要操作的token为某种类型时使用的方法. 直接对token进行操作时, 使用的是getToken()方法(取得当前token, 并指向下一个token位置)
	private Token peekToken() {
		if (this.tokenStreamPointer >= this.tokenStreamLength) {
			return null;
		}
		return this.tokenStream.get(this.tokenStreamPointer);
	}

	public String toString(@Nullable Token t) {
		if (t == null) {
			return "";
		}
		if (t.getKind().hasPayload()) {
			return t.stringValue();
		}
		return t.kind.toString().toLowerCase();
	}

	private void checkOperands(Token token, @Nullable SpelNodeImpl left, @Nullable SpelNodeImpl right) {
		checkLeftOperand(token, left);
		checkRightOperand(token, right);
	}

	private void checkLeftOperand(Token token, @Nullable SpelNodeImpl operandExpression) {
		if (operandExpression == null) {
			throw internalException(token.startPos, SpelMessage.LEFT_OPERAND_PROBLEM);
		}
	}

	private void checkRightOperand(Token token, @Nullable SpelNodeImpl operandExpression) {
		if (operandExpression == null) {
			throw internalException(token.startPos, SpelMessage.RIGHT_OPERAND_PROBLEM);
		}
	}

	private InternalParseException internalException(int startPos, SpelMessage message, Object... inserts) {
		return new InternalParseException(new SpelParseException(this.expressionString, startPos, message, inserts));
	}

}
