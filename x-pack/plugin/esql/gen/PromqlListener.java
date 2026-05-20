// Generated from /Users/chegar/git/elasticsearch-serverless/elasticsearch/x-pack/plugin/esql/src/main/antlr/parser/Promql.g4 by ANTLR 4.13.2
import org.antlr.v4.runtime.tree.ParseTreeListener;

/**
 * This interface defines a complete listener for a parse tree produced by
 * {@link Promql}.
 */
public interface PromqlListener extends ParseTreeListener {
	/**
	 * Enter a parse tree produced by {@link Promql#promqlCommand}.
	 * @param ctx the parse tree
	 */
	void enterPromqlCommand(Promql.PromqlCommandContext ctx);
	/**
	 * Exit a parse tree produced by {@link Promql#promqlCommand}.
	 * @param ctx the parse tree
	 */
	void exitPromqlCommand(Promql.PromqlCommandContext ctx);
	/**
	 * Enter a parse tree produced by {@link Promql#valueName}.
	 * @param ctx the parse tree
	 */
	void enterValueName(Promql.ValueNameContext ctx);
	/**
	 * Exit a parse tree produced by {@link Promql#valueName}.
	 * @param ctx the parse tree
	 */
	void exitValueName(Promql.ValueNameContext ctx);
	/**
	 * Enter a parse tree produced by {@link Promql#promqlParam}.
	 * @param ctx the parse tree
	 */
	void enterPromqlParam(Promql.PromqlParamContext ctx);
	/**
	 * Exit a parse tree produced by {@link Promql#promqlParam}.
	 * @param ctx the parse tree
	 */
	void exitPromqlParam(Promql.PromqlParamContext ctx);
	/**
	 * Enter a parse tree produced by {@link Promql#promqlParamName}.
	 * @param ctx the parse tree
	 */
	void enterPromqlParamName(Promql.PromqlParamNameContext ctx);
	/**
	 * Exit a parse tree produced by {@link Promql#promqlParamName}.
	 * @param ctx the parse tree
	 */
	void exitPromqlParamName(Promql.PromqlParamNameContext ctx);
	/**
	 * Enter a parse tree produced by {@link Promql#promqlParamValue}.
	 * @param ctx the parse tree
	 */
	void enterPromqlParamValue(Promql.PromqlParamValueContext ctx);
	/**
	 * Exit a parse tree produced by {@link Promql#promqlParamValue}.
	 * @param ctx the parse tree
	 */
	void exitPromqlParamValue(Promql.PromqlParamValueContext ctx);
	/**
	 * Enter a parse tree produced by {@link Promql#promqlQueryContent}.
	 * @param ctx the parse tree
	 */
	void enterPromqlQueryContent(Promql.PromqlQueryContentContext ctx);
	/**
	 * Exit a parse tree produced by {@link Promql#promqlQueryContent}.
	 * @param ctx the parse tree
	 */
	void exitPromqlQueryContent(Promql.PromqlQueryContentContext ctx);
	/**
	 * Enter a parse tree produced by {@link Promql#promqlQueryPart}.
	 * @param ctx the parse tree
	 */
	void enterPromqlQueryPart(Promql.PromqlQueryPartContext ctx);
	/**
	 * Exit a parse tree produced by {@link Promql#promqlQueryPart}.
	 * @param ctx the parse tree
	 */
	void exitPromqlQueryPart(Promql.PromqlQueryPartContext ctx);
	/**
	 * Enter a parse tree produced by {@link Promql#promqlIndexPattern}.
	 * @param ctx the parse tree
	 */
	void enterPromqlIndexPattern(Promql.PromqlIndexPatternContext ctx);
	/**
	 * Exit a parse tree produced by {@link Promql#promqlIndexPattern}.
	 * @param ctx the parse tree
	 */
	void exitPromqlIndexPattern(Promql.PromqlIndexPatternContext ctx);
	/**
	 * Enter a parse tree produced by {@link Promql#promqlClusterString}.
	 * @param ctx the parse tree
	 */
	void enterPromqlClusterString(Promql.PromqlClusterStringContext ctx);
	/**
	 * Exit a parse tree produced by {@link Promql#promqlClusterString}.
	 * @param ctx the parse tree
	 */
	void exitPromqlClusterString(Promql.PromqlClusterStringContext ctx);
	/**
	 * Enter a parse tree produced by {@link Promql#promqlSelectorString}.
	 * @param ctx the parse tree
	 */
	void enterPromqlSelectorString(Promql.PromqlSelectorStringContext ctx);
	/**
	 * Exit a parse tree produced by {@link Promql#promqlSelectorString}.
	 * @param ctx the parse tree
	 */
	void exitPromqlSelectorString(Promql.PromqlSelectorStringContext ctx);
	/**
	 * Enter a parse tree produced by {@link Promql#promqlUnquotedIndexString}.
	 * @param ctx the parse tree
	 */
	void enterPromqlUnquotedIndexString(Promql.PromqlUnquotedIndexStringContext ctx);
	/**
	 * Exit a parse tree produced by {@link Promql#promqlUnquotedIndexString}.
	 * @param ctx the parse tree
	 */
	void exitPromqlUnquotedIndexString(Promql.PromqlUnquotedIndexStringContext ctx);
	/**
	 * Enter a parse tree produced by {@link Promql#promqlIndexString}.
	 * @param ctx the parse tree
	 */
	void enterPromqlIndexString(Promql.PromqlIndexStringContext ctx);
	/**
	 * Exit a parse tree produced by {@link Promql#promqlIndexString}.
	 * @param ctx the parse tree
	 */
	void exitPromqlIndexString(Promql.PromqlIndexStringContext ctx);
}