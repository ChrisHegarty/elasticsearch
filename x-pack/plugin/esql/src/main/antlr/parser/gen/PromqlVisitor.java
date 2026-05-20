// Generated from /Users/chegar/git/elasticsearch-serverless/elasticsearch/x-pack/plugin/esql/src/main/antlr/parser/Promql.g4 by ANTLR 4.13.2
import org.antlr.v4.runtime.tree.ParseTreeVisitor;

/**
 * This interface defines a complete generic visitor for a parse tree produced
 * by {@link Promql}.
 *
 * @param <T> The return type of the visit operation. Use {@link Void} for
 * operations with no return type.
 */
public interface PromqlVisitor<T> extends ParseTreeVisitor<T> {
	/**
	 * Visit a parse tree produced by {@link Promql#promqlCommand}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPromqlCommand(Promql.PromqlCommandContext ctx);
	/**
	 * Visit a parse tree produced by {@link Promql#valueName}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitValueName(Promql.ValueNameContext ctx);
	/**
	 * Visit a parse tree produced by {@link Promql#promqlParam}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPromqlParam(Promql.PromqlParamContext ctx);
	/**
	 * Visit a parse tree produced by {@link Promql#promqlParamName}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPromqlParamName(Promql.PromqlParamNameContext ctx);
	/**
	 * Visit a parse tree produced by {@link Promql#promqlParamValue}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPromqlParamValue(Promql.PromqlParamValueContext ctx);
	/**
	 * Visit a parse tree produced by {@link Promql#promqlQueryContent}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPromqlQueryContent(Promql.PromqlQueryContentContext ctx);
	/**
	 * Visit a parse tree produced by {@link Promql#promqlQueryPart}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPromqlQueryPart(Promql.PromqlQueryPartContext ctx);
	/**
	 * Visit a parse tree produced by {@link Promql#promqlIndexPattern}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPromqlIndexPattern(Promql.PromqlIndexPatternContext ctx);
	/**
	 * Visit a parse tree produced by {@link Promql#promqlClusterString}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPromqlClusterString(Promql.PromqlClusterStringContext ctx);
	/**
	 * Visit a parse tree produced by {@link Promql#promqlSelectorString}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPromqlSelectorString(Promql.PromqlSelectorStringContext ctx);
	/**
	 * Visit a parse tree produced by {@link Promql#promqlUnquotedIndexString}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPromqlUnquotedIndexString(Promql.PromqlUnquotedIndexStringContext ctx);
	/**
	 * Visit a parse tree produced by {@link Promql#promqlIndexString}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPromqlIndexString(Promql.PromqlIndexStringContext ctx);
}