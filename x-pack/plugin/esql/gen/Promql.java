// Generated from /Users/chegar/git/elasticsearch-serverless/elasticsearch/x-pack/plugin/esql/src/main/antlr/parser/Promql.g4 by ANTLR 4.13.2
import org.antlr.v4.runtime.atn.*;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.misc.*;
import org.antlr.v4.runtime.tree.*;
import java.util.List;
import java.util.Iterator;
import java.util.ArrayList;

@SuppressWarnings({"all", "warnings", "unchecked", "unused", "cast", "CheckReturnValue", "this-escape"})
public class Promql extends Parser {
	static { RuntimeMetaData.checkVersion("4.13.2", RuntimeMetaData.VERSION); }

	protected static final DFA[] _decisionToDFA;
	protected static final PredictionContextCache _sharedContextCache =
		new PredictionContextCache();
	public static final int
		PROMQL=1, ASSIGN=2, LP=3, NAMED_OR_POSITIONAL_PARAM=4, RP=5, UNQUOTED_IDENTIFIER=6, 
		QUOTED_IDENTIFIER=7, QUOTED_STRING=8, COMMA=9, UNQUOTED_SOURCE=10, PROMQL_QUERY_COMMENT=11, 
		PROMQL_SINGLE_QUOTED_STRING=12, COLON=13, CAST_OP=14, PROMQL_OTHER_QUERY_CONTENT=15;
	public static final int
		RULE_promqlCommand = 0, RULE_valueName = 1, RULE_promqlParam = 2, RULE_promqlParamName = 3, 
		RULE_promqlParamValue = 4, RULE_promqlQueryContent = 5, RULE_promqlQueryPart = 6, 
		RULE_promqlIndexPattern = 7, RULE_promqlClusterString = 8, RULE_promqlSelectorString = 9, 
		RULE_promqlUnquotedIndexString = 10, RULE_promqlIndexString = 11;
	private static String[] makeRuleNames() {
		return new String[] {
			"promqlCommand", "valueName", "promqlParam", "promqlParamName", "promqlParamValue", 
			"promqlQueryContent", "promqlQueryPart", "promqlIndexPattern", "promqlClusterString", 
			"promqlSelectorString", "promqlUnquotedIndexString", "promqlIndexString"
		};
	}
	public static final String[] ruleNames = makeRuleNames();

	private static String[] makeLiteralNames() {
		return new String[] {
		};
	}
	private static final String[] _LITERAL_NAMES = makeLiteralNames();
	private static String[] makeSymbolicNames() {
		return new String[] {
			null, "PROMQL", "ASSIGN", "LP", "NAMED_OR_POSITIONAL_PARAM", "RP", "UNQUOTED_IDENTIFIER", 
			"QUOTED_IDENTIFIER", "QUOTED_STRING", "COMMA", "UNQUOTED_SOURCE", "PROMQL_QUERY_COMMENT", 
			"PROMQL_SINGLE_QUOTED_STRING", "COLON", "CAST_OP", "PROMQL_OTHER_QUERY_CONTENT"
		};
	}
	private static final String[] _SYMBOLIC_NAMES = makeSymbolicNames();
	public static final Vocabulary VOCABULARY = new VocabularyImpl(_LITERAL_NAMES, _SYMBOLIC_NAMES);

	/**
	 * @deprecated Use {@link #VOCABULARY} instead.
	 */
	@Deprecated
	public static final String[] tokenNames;
	static {
		tokenNames = new String[_SYMBOLIC_NAMES.length];
		for (int i = 0; i < tokenNames.length; i++) {
			tokenNames[i] = VOCABULARY.getLiteralName(i);
			if (tokenNames[i] == null) {
				tokenNames[i] = VOCABULARY.getSymbolicName(i);
			}

			if (tokenNames[i] == null) {
				tokenNames[i] = "<INVALID>";
			}
		}
	}

	@Override
	@Deprecated
	public String[] getTokenNames() {
		return tokenNames;
	}

	@Override

	public Vocabulary getVocabulary() {
		return VOCABULARY;
	}

	@Override
	public String getGrammarFileName() { return "Promql.g4"; }

	@Override
	public String[] getRuleNames() { return ruleNames; }

	@Override
	public String getSerializedATN() { return _serializedATN; }

	@Override
	public ATN getATN() { return _ATN; }

	public Promql(TokenStream input) {
		super(input);
		_interp = new ParserATNSimulator(this,_ATN,_decisionToDFA,_sharedContextCache);
	}

	@SuppressWarnings("CheckReturnValue")
	public static class PromqlCommandContext extends ParserRuleContext {
		public TerminalNode PROMQL() { return getToken(Promql.PROMQL, 0); }
		public TerminalNode LP() { return getToken(Promql.LP, 0); }
		public TerminalNode NAMED_OR_POSITIONAL_PARAM() { return getToken(Promql.NAMED_OR_POSITIONAL_PARAM, 0); }
		public TerminalNode RP() { return getToken(Promql.RP, 0); }
		public List<PromqlParamContext> promqlParam() {
			return getRuleContexts(PromqlParamContext.class);
		}
		public PromqlParamContext promqlParam(int i) {
			return getRuleContext(PromqlParamContext.class,i);
		}
		public ValueNameContext valueName() {
			return getRuleContext(ValueNameContext.class,0);
		}
		public TerminalNode ASSIGN() { return getToken(Promql.ASSIGN, 0); }
		public List<PromqlQueryPartContext> promqlQueryPart() {
			return getRuleContexts(PromqlQueryPartContext.class);
		}
		public PromqlQueryPartContext promqlQueryPart(int i) {
			return getRuleContext(PromqlQueryPartContext.class,i);
		}
		public PromqlCommandContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_promqlCommand; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof PromqlListener ) ((PromqlListener)listener).enterPromqlCommand(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof PromqlListener ) ((PromqlListener)listener).exitPromqlCommand(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PromqlVisitor ) return ((PromqlVisitor<? extends T>)visitor).visitPromqlCommand(this);
			else return visitor.visitChildren(this);
		}
	}

	public final PromqlCommandContext promqlCommand() throws RecognitionException {
		PromqlCommandContext _localctx = new PromqlCommandContext(_ctx, getState());
		enterRule(_localctx, 0, RULE_promqlCommand);
		int _la;
		try {
			int _alt;
			setState(84);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,9,_ctx) ) {
			case 1:
				enterOuterAlt(_localctx, 1);
				{
				setState(24);
				match(PROMQL);
				setState(28);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,0,_ctx);
				while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
					if ( _alt==1 ) {
						{
						{
						setState(25);
						promqlParam();
						}
						} 
					}
					setState(30);
					_errHandler.sync(this);
					_alt = getInterpreter().adaptivePredict(_input,0,_ctx);
				}
				setState(34);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (_la==UNQUOTED_IDENTIFIER || _la==QUOTED_IDENTIFIER) {
					{
					setState(31);
					valueName();
					setState(32);
					match(ASSIGN);
					}
				}

				setState(36);
				match(LP);
				setState(37);
				match(NAMED_OR_POSITIONAL_PARAM);
				setState(38);
				match(RP);
				}
				break;
			case 2:
				enterOuterAlt(_localctx, 2);
				{
				setState(39);
				match(PROMQL);
				setState(43);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,2,_ctx);
				while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
					if ( _alt==1 ) {
						{
						{
						setState(40);
						promqlParam();
						}
						} 
					}
					setState(45);
					_errHandler.sync(this);
					_alt = getInterpreter().adaptivePredict(_input,2,_ctx);
				}
				setState(49);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (_la==UNQUOTED_IDENTIFIER || _la==QUOTED_IDENTIFIER) {
					{
					setState(46);
					valueName();
					setState(47);
					match(ASSIGN);
					}
				}

				setState(51);
				match(NAMED_OR_POSITIONAL_PARAM);
				}
				break;
			case 3:
				enterOuterAlt(_localctx, 3);
				{
				setState(52);
				match(PROMQL);
				setState(56);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,4,_ctx);
				while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
					if ( _alt==1 ) {
						{
						{
						setState(53);
						promqlParam();
						}
						} 
					}
					setState(58);
					_errHandler.sync(this);
					_alt = getInterpreter().adaptivePredict(_input,4,_ctx);
				}
				setState(62);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (_la==UNQUOTED_IDENTIFIER || _la==QUOTED_IDENTIFIER) {
					{
					setState(59);
					valueName();
					setState(60);
					match(ASSIGN);
					}
				}

				setState(64);
				match(LP);
				setState(66); 
				_errHandler.sync(this);
				_la = _input.LA(1);
				do {
					{
					{
					setState(65);
					promqlQueryPart();
					}
					}
					setState(68); 
					_errHandler.sync(this);
					_la = _input.LA(1);
				} while ( (((_la) & ~0x3f) == 0 && ((1L << _la) & 65500L) != 0) );
				setState(70);
				match(RP);
				}
				break;
			case 4:
				enterOuterAlt(_localctx, 4);
				{
				setState(72);
				match(PROMQL);
				setState(76);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,7,_ctx);
				while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
					if ( _alt==1 ) {
						{
						{
						setState(73);
						promqlParam();
						}
						} 
					}
					setState(78);
					_errHandler.sync(this);
					_alt = getInterpreter().adaptivePredict(_input,7,_ctx);
				}
				setState(80); 
				_errHandler.sync(this);
				_la = _input.LA(1);
				do {
					{
					{
					setState(79);
					promqlQueryPart();
					}
					}
					setState(82); 
					_errHandler.sync(this);
					_la = _input.LA(1);
				} while ( (((_la) & ~0x3f) == 0 && ((1L << _la) & 65500L) != 0) );
				}
				break;
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class ValueNameContext extends ParserRuleContext {
		public TerminalNode UNQUOTED_IDENTIFIER() { return getToken(Promql.UNQUOTED_IDENTIFIER, 0); }
		public TerminalNode QUOTED_IDENTIFIER() { return getToken(Promql.QUOTED_IDENTIFIER, 0); }
		public ValueNameContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_valueName; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof PromqlListener ) ((PromqlListener)listener).enterValueName(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof PromqlListener ) ((PromqlListener)listener).exitValueName(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PromqlVisitor ) return ((PromqlVisitor<? extends T>)visitor).visitValueName(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ValueNameContext valueName() throws RecognitionException {
		ValueNameContext _localctx = new ValueNameContext(_ctx, getState());
		enterRule(_localctx, 2, RULE_valueName);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(86);
			_la = _input.LA(1);
			if ( !(_la==UNQUOTED_IDENTIFIER || _la==QUOTED_IDENTIFIER) ) {
			_errHandler.recoverInline(this);
			}
			else {
				if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
				_errHandler.reportMatch(this);
				consume();
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class PromqlParamContext extends ParserRuleContext {
		public PromqlParamNameContext name;
		public PromqlParamValueContext value;
		public TerminalNode ASSIGN() { return getToken(Promql.ASSIGN, 0); }
		public PromqlParamNameContext promqlParamName() {
			return getRuleContext(PromqlParamNameContext.class,0);
		}
		public PromqlParamValueContext promqlParamValue() {
			return getRuleContext(PromqlParamValueContext.class,0);
		}
		public PromqlParamContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_promqlParam; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof PromqlListener ) ((PromqlListener)listener).enterPromqlParam(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof PromqlListener ) ((PromqlListener)listener).exitPromqlParam(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PromqlVisitor ) return ((PromqlVisitor<? extends T>)visitor).visitPromqlParam(this);
			else return visitor.visitChildren(this);
		}
	}

	public final PromqlParamContext promqlParam() throws RecognitionException {
		PromqlParamContext _localctx = new PromqlParamContext(_ctx, getState());
		enterRule(_localctx, 4, RULE_promqlParam);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(88);
			((PromqlParamContext)_localctx).name = promqlParamName();
			setState(89);
			match(ASSIGN);
			setState(90);
			((PromqlParamContext)_localctx).value = promqlParamValue();
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class PromqlParamNameContext extends ParserRuleContext {
		public TerminalNode UNQUOTED_IDENTIFIER() { return getToken(Promql.UNQUOTED_IDENTIFIER, 0); }
		public TerminalNode QUOTED_IDENTIFIER() { return getToken(Promql.QUOTED_IDENTIFIER, 0); }
		public TerminalNode QUOTED_STRING() { return getToken(Promql.QUOTED_STRING, 0); }
		public TerminalNode NAMED_OR_POSITIONAL_PARAM() { return getToken(Promql.NAMED_OR_POSITIONAL_PARAM, 0); }
		public PromqlParamNameContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_promqlParamName; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof PromqlListener ) ((PromqlListener)listener).enterPromqlParamName(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof PromqlListener ) ((PromqlListener)listener).exitPromqlParamName(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PromqlVisitor ) return ((PromqlVisitor<? extends T>)visitor).visitPromqlParamName(this);
			else return visitor.visitChildren(this);
		}
	}

	public final PromqlParamNameContext promqlParamName() throws RecognitionException {
		PromqlParamNameContext _localctx = new PromqlParamNameContext(_ctx, getState());
		enterRule(_localctx, 6, RULE_promqlParamName);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(92);
			_la = _input.LA(1);
			if ( !((((_la) & ~0x3f) == 0 && ((1L << _la) & 464L) != 0)) ) {
			_errHandler.recoverInline(this);
			}
			else {
				if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
				_errHandler.reportMatch(this);
				consume();
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class PromqlParamValueContext extends ParserRuleContext {
		public List<PromqlIndexPatternContext> promqlIndexPattern() {
			return getRuleContexts(PromqlIndexPatternContext.class);
		}
		public PromqlIndexPatternContext promqlIndexPattern(int i) {
			return getRuleContext(PromqlIndexPatternContext.class,i);
		}
		public List<TerminalNode> COMMA() { return getTokens(Promql.COMMA); }
		public TerminalNode COMMA(int i) {
			return getToken(Promql.COMMA, i);
		}
		public TerminalNode QUOTED_IDENTIFIER() { return getToken(Promql.QUOTED_IDENTIFIER, 0); }
		public TerminalNode NAMED_OR_POSITIONAL_PARAM() { return getToken(Promql.NAMED_OR_POSITIONAL_PARAM, 0); }
		public PromqlParamValueContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_promqlParamValue; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof PromqlListener ) ((PromqlListener)listener).enterPromqlParamValue(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof PromqlListener ) ((PromqlListener)listener).exitPromqlParamValue(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PromqlVisitor ) return ((PromqlVisitor<? extends T>)visitor).visitPromqlParamValue(this);
			else return visitor.visitChildren(this);
		}
	}

	public final PromqlParamValueContext promqlParamValue() throws RecognitionException {
		PromqlParamValueContext _localctx = new PromqlParamValueContext(_ctx, getState());
		enterRule(_localctx, 8, RULE_promqlParamValue);
		try {
			int _alt;
			setState(104);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case UNQUOTED_IDENTIFIER:
			case QUOTED_STRING:
			case UNQUOTED_SOURCE:
				enterOuterAlt(_localctx, 1);
				{
				setState(94);
				promqlIndexPattern();
				setState(99);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,10,_ctx);
				while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
					if ( _alt==1 ) {
						{
						{
						setState(95);
						match(COMMA);
						setState(96);
						promqlIndexPattern();
						}
						} 
					}
					setState(101);
					_errHandler.sync(this);
					_alt = getInterpreter().adaptivePredict(_input,10,_ctx);
				}
				}
				break;
			case QUOTED_IDENTIFIER:
				enterOuterAlt(_localctx, 2);
				{
				setState(102);
				match(QUOTED_IDENTIFIER);
				}
				break;
			case NAMED_OR_POSITIONAL_PARAM:
				enterOuterAlt(_localctx, 3);
				{
				setState(103);
				match(NAMED_OR_POSITIONAL_PARAM);
				}
				break;
			default:
				throw new NoViableAltException(this);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class PromqlQueryContentContext extends ParserRuleContext {
		public TerminalNode UNQUOTED_SOURCE() { return getToken(Promql.UNQUOTED_SOURCE, 0); }
		public TerminalNode UNQUOTED_IDENTIFIER() { return getToken(Promql.UNQUOTED_IDENTIFIER, 0); }
		public TerminalNode QUOTED_STRING() { return getToken(Promql.QUOTED_STRING, 0); }
		public TerminalNode QUOTED_IDENTIFIER() { return getToken(Promql.QUOTED_IDENTIFIER, 0); }
		public TerminalNode NAMED_OR_POSITIONAL_PARAM() { return getToken(Promql.NAMED_OR_POSITIONAL_PARAM, 0); }
		public TerminalNode PROMQL_QUERY_COMMENT() { return getToken(Promql.PROMQL_QUERY_COMMENT, 0); }
		public TerminalNode PROMQL_SINGLE_QUOTED_STRING() { return getToken(Promql.PROMQL_SINGLE_QUOTED_STRING, 0); }
		public TerminalNode ASSIGN() { return getToken(Promql.ASSIGN, 0); }
		public TerminalNode COMMA() { return getToken(Promql.COMMA, 0); }
		public TerminalNode COLON() { return getToken(Promql.COLON, 0); }
		public TerminalNode CAST_OP() { return getToken(Promql.CAST_OP, 0); }
		public TerminalNode PROMQL_OTHER_QUERY_CONTENT() { return getToken(Promql.PROMQL_OTHER_QUERY_CONTENT, 0); }
		public PromqlQueryContentContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_promqlQueryContent; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof PromqlListener ) ((PromqlListener)listener).enterPromqlQueryContent(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof PromqlListener ) ((PromqlListener)listener).exitPromqlQueryContent(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PromqlVisitor ) return ((PromqlVisitor<? extends T>)visitor).visitPromqlQueryContent(this);
			else return visitor.visitChildren(this);
		}
	}

	public final PromqlQueryContentContext promqlQueryContent() throws RecognitionException {
		PromqlQueryContentContext _localctx = new PromqlQueryContentContext(_ctx, getState());
		enterRule(_localctx, 10, RULE_promqlQueryContent);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(106);
			_la = _input.LA(1);
			if ( !((((_la) & ~0x3f) == 0 && ((1L << _la) & 65492L) != 0)) ) {
			_errHandler.recoverInline(this);
			}
			else {
				if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
				_errHandler.reportMatch(this);
				consume();
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class PromqlQueryPartContext extends ParserRuleContext {
		public List<PromqlQueryContentContext> promqlQueryContent() {
			return getRuleContexts(PromqlQueryContentContext.class);
		}
		public PromqlQueryContentContext promqlQueryContent(int i) {
			return getRuleContext(PromqlQueryContentContext.class,i);
		}
		public TerminalNode LP() { return getToken(Promql.LP, 0); }
		public TerminalNode RP() { return getToken(Promql.RP, 0); }
		public List<PromqlQueryPartContext> promqlQueryPart() {
			return getRuleContexts(PromqlQueryPartContext.class);
		}
		public PromqlQueryPartContext promqlQueryPart(int i) {
			return getRuleContext(PromqlQueryPartContext.class,i);
		}
		public PromqlQueryPartContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_promqlQueryPart; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof PromqlListener ) ((PromqlListener)listener).enterPromqlQueryPart(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof PromqlListener ) ((PromqlListener)listener).exitPromqlQueryPart(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PromqlVisitor ) return ((PromqlVisitor<? extends T>)visitor).visitPromqlQueryPart(this);
			else return visitor.visitChildren(this);
		}
	}

	public final PromqlQueryPartContext promqlQueryPart() throws RecognitionException {
		PromqlQueryPartContext _localctx = new PromqlQueryPartContext(_ctx, getState());
		enterRule(_localctx, 12, RULE_promqlQueryPart);
		int _la;
		try {
			int _alt;
			setState(121);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case ASSIGN:
			case NAMED_OR_POSITIONAL_PARAM:
			case UNQUOTED_IDENTIFIER:
			case QUOTED_IDENTIFIER:
			case QUOTED_STRING:
			case COMMA:
			case UNQUOTED_SOURCE:
			case PROMQL_QUERY_COMMENT:
			case PROMQL_SINGLE_QUOTED_STRING:
			case COLON:
			case CAST_OP:
			case PROMQL_OTHER_QUERY_CONTENT:
				enterOuterAlt(_localctx, 1);
				{
				setState(109); 
				_errHandler.sync(this);
				_alt = 1;
				do {
					switch (_alt) {
					case 1:
						{
						{
						setState(108);
						promqlQueryContent();
						}
						}
						break;
					default:
						throw new NoViableAltException(this);
					}
					setState(111); 
					_errHandler.sync(this);
					_alt = getInterpreter().adaptivePredict(_input,12,_ctx);
				} while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER );
				}
				break;
			case LP:
				enterOuterAlt(_localctx, 2);
				{
				setState(113);
				match(LP);
				setState(117);
				_errHandler.sync(this);
				_la = _input.LA(1);
				while ((((_la) & ~0x3f) == 0 && ((1L << _la) & 65500L) != 0)) {
					{
					{
					setState(114);
					promqlQueryPart();
					}
					}
					setState(119);
					_errHandler.sync(this);
					_la = _input.LA(1);
				}
				setState(120);
				match(RP);
				}
				break;
			default:
				throw new NoViableAltException(this);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class PromqlIndexPatternContext extends ParserRuleContext {
		public PromqlClusterStringContext promqlClusterString() {
			return getRuleContext(PromqlClusterStringContext.class,0);
		}
		public TerminalNode COLON() { return getToken(Promql.COLON, 0); }
		public PromqlUnquotedIndexStringContext promqlUnquotedIndexString() {
			return getRuleContext(PromqlUnquotedIndexStringContext.class,0);
		}
		public TerminalNode CAST_OP() { return getToken(Promql.CAST_OP, 0); }
		public PromqlSelectorStringContext promqlSelectorString() {
			return getRuleContext(PromqlSelectorStringContext.class,0);
		}
		public PromqlIndexStringContext promqlIndexString() {
			return getRuleContext(PromqlIndexStringContext.class,0);
		}
		public PromqlIndexPatternContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_promqlIndexPattern; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof PromqlListener ) ((PromqlListener)listener).enterPromqlIndexPattern(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof PromqlListener ) ((PromqlListener)listener).exitPromqlIndexPattern(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PromqlVisitor ) return ((PromqlVisitor<? extends T>)visitor).visitPromqlIndexPattern(this);
			else return visitor.visitChildren(this);
		}
	}

	public final PromqlIndexPatternContext promqlIndexPattern() throws RecognitionException {
		PromqlIndexPatternContext _localctx = new PromqlIndexPatternContext(_ctx, getState());
		enterRule(_localctx, 14, RULE_promqlIndexPattern);
		try {
			setState(132);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,15,_ctx) ) {
			case 1:
				enterOuterAlt(_localctx, 1);
				{
				setState(123);
				promqlClusterString();
				setState(124);
				match(COLON);
				setState(125);
				promqlUnquotedIndexString();
				}
				break;
			case 2:
				enterOuterAlt(_localctx, 2);
				{
				setState(127);
				promqlUnquotedIndexString();
				setState(128);
				match(CAST_OP);
				setState(129);
				promqlSelectorString();
				}
				break;
			case 3:
				enterOuterAlt(_localctx, 3);
				{
				setState(131);
				promqlIndexString();
				}
				break;
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class PromqlClusterStringContext extends ParserRuleContext {
		public TerminalNode UNQUOTED_IDENTIFIER() { return getToken(Promql.UNQUOTED_IDENTIFIER, 0); }
		public TerminalNode UNQUOTED_SOURCE() { return getToken(Promql.UNQUOTED_SOURCE, 0); }
		public PromqlClusterStringContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_promqlClusterString; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof PromqlListener ) ((PromqlListener)listener).enterPromqlClusterString(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof PromqlListener ) ((PromqlListener)listener).exitPromqlClusterString(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PromqlVisitor ) return ((PromqlVisitor<? extends T>)visitor).visitPromqlClusterString(this);
			else return visitor.visitChildren(this);
		}
	}

	public final PromqlClusterStringContext promqlClusterString() throws RecognitionException {
		PromqlClusterStringContext _localctx = new PromqlClusterStringContext(_ctx, getState());
		enterRule(_localctx, 16, RULE_promqlClusterString);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(134);
			_la = _input.LA(1);
			if ( !(_la==UNQUOTED_IDENTIFIER || _la==UNQUOTED_SOURCE) ) {
			_errHandler.recoverInline(this);
			}
			else {
				if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
				_errHandler.reportMatch(this);
				consume();
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class PromqlSelectorStringContext extends ParserRuleContext {
		public TerminalNode UNQUOTED_IDENTIFIER() { return getToken(Promql.UNQUOTED_IDENTIFIER, 0); }
		public TerminalNode UNQUOTED_SOURCE() { return getToken(Promql.UNQUOTED_SOURCE, 0); }
		public PromqlSelectorStringContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_promqlSelectorString; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof PromqlListener ) ((PromqlListener)listener).enterPromqlSelectorString(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof PromqlListener ) ((PromqlListener)listener).exitPromqlSelectorString(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PromqlVisitor ) return ((PromqlVisitor<? extends T>)visitor).visitPromqlSelectorString(this);
			else return visitor.visitChildren(this);
		}
	}

	public final PromqlSelectorStringContext promqlSelectorString() throws RecognitionException {
		PromqlSelectorStringContext _localctx = new PromqlSelectorStringContext(_ctx, getState());
		enterRule(_localctx, 18, RULE_promqlSelectorString);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(136);
			_la = _input.LA(1);
			if ( !(_la==UNQUOTED_IDENTIFIER || _la==UNQUOTED_SOURCE) ) {
			_errHandler.recoverInline(this);
			}
			else {
				if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
				_errHandler.reportMatch(this);
				consume();
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class PromqlUnquotedIndexStringContext extends ParserRuleContext {
		public TerminalNode UNQUOTED_IDENTIFIER() { return getToken(Promql.UNQUOTED_IDENTIFIER, 0); }
		public TerminalNode UNQUOTED_SOURCE() { return getToken(Promql.UNQUOTED_SOURCE, 0); }
		public PromqlUnquotedIndexStringContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_promqlUnquotedIndexString; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof PromqlListener ) ((PromqlListener)listener).enterPromqlUnquotedIndexString(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof PromqlListener ) ((PromqlListener)listener).exitPromqlUnquotedIndexString(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PromqlVisitor ) return ((PromqlVisitor<? extends T>)visitor).visitPromqlUnquotedIndexString(this);
			else return visitor.visitChildren(this);
		}
	}

	public final PromqlUnquotedIndexStringContext promqlUnquotedIndexString() throws RecognitionException {
		PromqlUnquotedIndexStringContext _localctx = new PromqlUnquotedIndexStringContext(_ctx, getState());
		enterRule(_localctx, 20, RULE_promqlUnquotedIndexString);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(138);
			_la = _input.LA(1);
			if ( !(_la==UNQUOTED_IDENTIFIER || _la==UNQUOTED_SOURCE) ) {
			_errHandler.recoverInline(this);
			}
			else {
				if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
				_errHandler.reportMatch(this);
				consume();
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class PromqlIndexStringContext extends ParserRuleContext {
		public TerminalNode UNQUOTED_IDENTIFIER() { return getToken(Promql.UNQUOTED_IDENTIFIER, 0); }
		public TerminalNode UNQUOTED_SOURCE() { return getToken(Promql.UNQUOTED_SOURCE, 0); }
		public TerminalNode QUOTED_STRING() { return getToken(Promql.QUOTED_STRING, 0); }
		public PromqlIndexStringContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_promqlIndexString; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof PromqlListener ) ((PromqlListener)listener).enterPromqlIndexString(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof PromqlListener ) ((PromqlListener)listener).exitPromqlIndexString(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof PromqlVisitor ) return ((PromqlVisitor<? extends T>)visitor).visitPromqlIndexString(this);
			else return visitor.visitChildren(this);
		}
	}

	public final PromqlIndexStringContext promqlIndexString() throws RecognitionException {
		PromqlIndexStringContext _localctx = new PromqlIndexStringContext(_ctx, getState());
		enterRule(_localctx, 22, RULE_promqlIndexString);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(140);
			_la = _input.LA(1);
			if ( !((((_la) & ~0x3f) == 0 && ((1L << _la) & 1344L) != 0)) ) {
			_errHandler.recoverInline(this);
			}
			else {
				if ( _input.LA(1)==Token.EOF ) matchedEOF = true;
				_errHandler.reportMatch(this);
				consume();
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static final String _serializedATN =
		"\u0004\u0001\u000f\u008f\u0002\u0000\u0007\u0000\u0002\u0001\u0007\u0001"+
		"\u0002\u0002\u0007\u0002\u0002\u0003\u0007\u0003\u0002\u0004\u0007\u0004"+
		"\u0002\u0005\u0007\u0005\u0002\u0006\u0007\u0006\u0002\u0007\u0007\u0007"+
		"\u0002\b\u0007\b\u0002\t\u0007\t\u0002\n\u0007\n\u0002\u000b\u0007\u000b"+
		"\u0001\u0000\u0001\u0000\u0005\u0000\u001b\b\u0000\n\u0000\f\u0000\u001e"+
		"\t\u0000\u0001\u0000\u0001\u0000\u0001\u0000\u0003\u0000#\b\u0000\u0001"+
		"\u0000\u0001\u0000\u0001\u0000\u0001\u0000\u0001\u0000\u0005\u0000*\b"+
		"\u0000\n\u0000\f\u0000-\t\u0000\u0001\u0000\u0001\u0000\u0001\u0000\u0003"+
		"\u00002\b\u0000\u0001\u0000\u0001\u0000\u0001\u0000\u0005\u00007\b\u0000"+
		"\n\u0000\f\u0000:\t\u0000\u0001\u0000\u0001\u0000\u0001\u0000\u0003\u0000"+
		"?\b\u0000\u0001\u0000\u0001\u0000\u0004\u0000C\b\u0000\u000b\u0000\f\u0000"+
		"D\u0001\u0000\u0001\u0000\u0001\u0000\u0001\u0000\u0005\u0000K\b\u0000"+
		"\n\u0000\f\u0000N\t\u0000\u0001\u0000\u0004\u0000Q\b\u0000\u000b\u0000"+
		"\f\u0000R\u0003\u0000U\b\u0000\u0001\u0001\u0001\u0001\u0001\u0002\u0001"+
		"\u0002\u0001\u0002\u0001\u0002\u0001\u0003\u0001\u0003\u0001\u0004\u0001"+
		"\u0004\u0001\u0004\u0005\u0004b\b\u0004\n\u0004\f\u0004e\t\u0004\u0001"+
		"\u0004\u0001\u0004\u0003\u0004i\b\u0004\u0001\u0005\u0001\u0005\u0001"+
		"\u0006\u0004\u0006n\b\u0006\u000b\u0006\f\u0006o\u0001\u0006\u0001\u0006"+
		"\u0005\u0006t\b\u0006\n\u0006\f\u0006w\t\u0006\u0001\u0006\u0003\u0006"+
		"z\b\u0006\u0001\u0007\u0001\u0007\u0001\u0007\u0001\u0007\u0001\u0007"+
		"\u0001\u0007\u0001\u0007\u0001\u0007\u0001\u0007\u0003\u0007\u0085\b\u0007"+
		"\u0001\b\u0001\b\u0001\t\u0001\t\u0001\n\u0001\n\u0001\u000b\u0001\u000b"+
		"\u0001\u000b\u0000\u0000\f\u0000\u0002\u0004\u0006\b\n\f\u000e\u0010\u0012"+
		"\u0014\u0016\u0000\u0005\u0001\u0000\u0006\u0007\u0002\u0000\u0004\u0004"+
		"\u0006\b\u0003\u0000\u0002\u0002\u0004\u0004\u0006\u000f\u0002\u0000\u0006"+
		"\u0006\n\n\u0003\u0000\u0006\u0006\b\b\n\n\u0096\u0000T\u0001\u0000\u0000"+
		"\u0000\u0002V\u0001\u0000\u0000\u0000\u0004X\u0001\u0000\u0000\u0000\u0006"+
		"\\\u0001\u0000\u0000\u0000\bh\u0001\u0000\u0000\u0000\nj\u0001\u0000\u0000"+
		"\u0000\fy\u0001\u0000\u0000\u0000\u000e\u0084\u0001\u0000\u0000\u0000"+
		"\u0010\u0086\u0001\u0000\u0000\u0000\u0012\u0088\u0001\u0000\u0000\u0000"+
		"\u0014\u008a\u0001\u0000\u0000\u0000\u0016\u008c\u0001\u0000\u0000\u0000"+
		"\u0018\u001c\u0005\u0001\u0000\u0000\u0019\u001b\u0003\u0004\u0002\u0000"+
		"\u001a\u0019\u0001\u0000\u0000\u0000\u001b\u001e\u0001\u0000\u0000\u0000"+
		"\u001c\u001a\u0001\u0000\u0000\u0000\u001c\u001d\u0001\u0000\u0000\u0000"+
		"\u001d\"\u0001\u0000\u0000\u0000\u001e\u001c\u0001\u0000\u0000\u0000\u001f"+
		" \u0003\u0002\u0001\u0000 !\u0005\u0002\u0000\u0000!#\u0001\u0000\u0000"+
		"\u0000\"\u001f\u0001\u0000\u0000\u0000\"#\u0001\u0000\u0000\u0000#$\u0001"+
		"\u0000\u0000\u0000$%\u0005\u0003\u0000\u0000%&\u0005\u0004\u0000\u0000"+
		"&U\u0005\u0005\u0000\u0000\'+\u0005\u0001\u0000\u0000(*\u0003\u0004\u0002"+
		"\u0000)(\u0001\u0000\u0000\u0000*-\u0001\u0000\u0000\u0000+)\u0001\u0000"+
		"\u0000\u0000+,\u0001\u0000\u0000\u0000,1\u0001\u0000\u0000\u0000-+\u0001"+
		"\u0000\u0000\u0000./\u0003\u0002\u0001\u0000/0\u0005\u0002\u0000\u0000"+
		"02\u0001\u0000\u0000\u00001.\u0001\u0000\u0000\u000012\u0001\u0000\u0000"+
		"\u000023\u0001\u0000\u0000\u00003U\u0005\u0004\u0000\u000048\u0005\u0001"+
		"\u0000\u000057\u0003\u0004\u0002\u000065\u0001\u0000\u0000\u00007:\u0001"+
		"\u0000\u0000\u000086\u0001\u0000\u0000\u000089\u0001\u0000\u0000\u0000"+
		"9>\u0001\u0000\u0000\u0000:8\u0001\u0000\u0000\u0000;<\u0003\u0002\u0001"+
		"\u0000<=\u0005\u0002\u0000\u0000=?\u0001\u0000\u0000\u0000>;\u0001\u0000"+
		"\u0000\u0000>?\u0001\u0000\u0000\u0000?@\u0001\u0000\u0000\u0000@B\u0005"+
		"\u0003\u0000\u0000AC\u0003\f\u0006\u0000BA\u0001\u0000\u0000\u0000CD\u0001"+
		"\u0000\u0000\u0000DB\u0001\u0000\u0000\u0000DE\u0001\u0000\u0000\u0000"+
		"EF\u0001\u0000\u0000\u0000FG\u0005\u0005\u0000\u0000GU\u0001\u0000\u0000"+
		"\u0000HL\u0005\u0001\u0000\u0000IK\u0003\u0004\u0002\u0000JI\u0001\u0000"+
		"\u0000\u0000KN\u0001\u0000\u0000\u0000LJ\u0001\u0000\u0000\u0000LM\u0001"+
		"\u0000\u0000\u0000MP\u0001\u0000\u0000\u0000NL\u0001\u0000\u0000\u0000"+
		"OQ\u0003\f\u0006\u0000PO\u0001\u0000\u0000\u0000QR\u0001\u0000\u0000\u0000"+
		"RP\u0001\u0000\u0000\u0000RS\u0001\u0000\u0000\u0000SU\u0001\u0000\u0000"+
		"\u0000T\u0018\u0001\u0000\u0000\u0000T\'\u0001\u0000\u0000\u0000T4\u0001"+
		"\u0000\u0000\u0000TH\u0001\u0000\u0000\u0000U\u0001\u0001\u0000\u0000"+
		"\u0000VW\u0007\u0000\u0000\u0000W\u0003\u0001\u0000\u0000\u0000XY\u0003"+
		"\u0006\u0003\u0000YZ\u0005\u0002\u0000\u0000Z[\u0003\b\u0004\u0000[\u0005"+
		"\u0001\u0000\u0000\u0000\\]\u0007\u0001\u0000\u0000]\u0007\u0001\u0000"+
		"\u0000\u0000^c\u0003\u000e\u0007\u0000_`\u0005\t\u0000\u0000`b\u0003\u000e"+
		"\u0007\u0000a_\u0001\u0000\u0000\u0000be\u0001\u0000\u0000\u0000ca\u0001"+
		"\u0000\u0000\u0000cd\u0001\u0000\u0000\u0000di\u0001\u0000\u0000\u0000"+
		"ec\u0001\u0000\u0000\u0000fi\u0005\u0007\u0000\u0000gi\u0005\u0004\u0000"+
		"\u0000h^\u0001\u0000\u0000\u0000hf\u0001\u0000\u0000\u0000hg\u0001\u0000"+
		"\u0000\u0000i\t\u0001\u0000\u0000\u0000jk\u0007\u0002\u0000\u0000k\u000b"+
		"\u0001\u0000\u0000\u0000ln\u0003\n\u0005\u0000ml\u0001\u0000\u0000\u0000"+
		"no\u0001\u0000\u0000\u0000om\u0001\u0000\u0000\u0000op\u0001\u0000\u0000"+
		"\u0000pz\u0001\u0000\u0000\u0000qu\u0005\u0003\u0000\u0000rt\u0003\f\u0006"+
		"\u0000sr\u0001\u0000\u0000\u0000tw\u0001\u0000\u0000\u0000us\u0001\u0000"+
		"\u0000\u0000uv\u0001\u0000\u0000\u0000vx\u0001\u0000\u0000\u0000wu\u0001"+
		"\u0000\u0000\u0000xz\u0005\u0005\u0000\u0000ym\u0001\u0000\u0000\u0000"+
		"yq\u0001\u0000\u0000\u0000z\r\u0001\u0000\u0000\u0000{|\u0003\u0010\b"+
		"\u0000|}\u0005\r\u0000\u0000}~\u0003\u0014\n\u0000~\u0085\u0001\u0000"+
		"\u0000\u0000\u007f\u0080\u0003\u0014\n\u0000\u0080\u0081\u0005\u000e\u0000"+
		"\u0000\u0081\u0082\u0003\u0012\t\u0000\u0082\u0085\u0001\u0000\u0000\u0000"+
		"\u0083\u0085\u0003\u0016\u000b\u0000\u0084{\u0001\u0000\u0000\u0000\u0084"+
		"\u007f\u0001\u0000\u0000\u0000\u0084\u0083\u0001\u0000\u0000\u0000\u0085"+
		"\u000f\u0001\u0000\u0000\u0000\u0086\u0087\u0007\u0003\u0000\u0000\u0087"+
		"\u0011\u0001\u0000\u0000\u0000\u0088\u0089\u0007\u0003\u0000\u0000\u0089"+
		"\u0013\u0001\u0000\u0000\u0000\u008a\u008b\u0007\u0003\u0000\u0000\u008b"+
		"\u0015\u0001\u0000\u0000\u0000\u008c\u008d\u0007\u0004\u0000\u0000\u008d"+
		"\u0017\u0001\u0000\u0000\u0000\u0010\u001c\"+18>DLRTchouy\u0084";
	public static final ATN _ATN =
		new ATNDeserializer().deserialize(_serializedATN.toCharArray());
	static {
		_decisionToDFA = new DFA[_ATN.getNumberOfDecisions()];
		for (int i = 0; i < _ATN.getNumberOfDecisions(); i++) {
			_decisionToDFA[i] = new DFA(_ATN.getDecisionState(i), i);
		}
	}
}