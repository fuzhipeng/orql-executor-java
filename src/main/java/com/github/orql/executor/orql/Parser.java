package com.github.orql.executor.orql;

import com.github.orql.executor.ExpOp;
import com.github.orql.executor.schema.Association;
import com.github.orql.executor.schema.Column;
import com.github.orql.executor.schema.Schema;
import com.github.orql.executor.schema.SchemaManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Parser {

    private static Map<String, OrqlNode> caches = new HashMap<>();

    private Lexer lexer;

    private SchemaManager schemaManager;

    private Token token;

    public Parser(SchemaManager schemaManager) {
        this.schemaManager = schemaManager;
    }

    private String matchToken(TokenType type) {
        if (type != token.getType()) throw new SyntaxException("expect " + type.name() + " actual " + token.getType());
        String string = token.getValue();
        walk();
        return string;
    }

    private boolean isToken(TokenType type) {
        return token.getType() == type;
    }

    private boolean isString(String string) {
        return token.getValue().equals(string);
    }

    private void walk() {
        token = lexer.nextToken();
    }

    public OrqlNode parse(String orql) {
        if (caches.containsKey(orql)) return caches.get(orql);
        this.lexer = new Lexer(orql);
        this.token = this.lexer.nextToken();
        OrqlNode node = visitReql();
        caches.put(orql, node);
        return node;
    }

    private OrqlNode visitReql() {
        String opStr = matchToken(TokenType.NAME);
        OrqlNode.ReqlOp op = OrqlNode.ReqlOp.fromName(opStr);
        return new OrqlNode(op, visitRoot());
    }

    private OrqlNode.ReqlRefItem visitRoot() {
        String name = matchToken(TokenType.NAME);
        Schema schema = schemaManager.getSchema(name);
        if (schema == null) throw new SyntaxException("schema " + name + " not exist");
        OrqlNode.ReqlWhere where = null;
        if (isToken(TokenType.OPEN_PAREN)) {
            // (
            this.walk();
            where = visitWhere(schema);
            // )
            this.matchToken(TokenType.CLOSE_PAREN);
        }
        if (isToken(TokenType.COLON)) {
            // :
            this.walk();
            if (isToken(TokenType.OPEN_CURLY)) {
                // {
                walk();
                List<OrqlNode.ReqlItem> items = visitItems(schema);
                // }
                matchToken(TokenType.CLOSE_CURLY);
                return new OrqlNode.ReqlObjectItem(name, schema, null, items, where);
            }
            if (isToken(TokenType.OPEN_BRACKET)) {
                // [
                walk();
                List<OrqlNode.ReqlItem> items = visitItems(schema);
                matchToken(TokenType.CLOSE_BRACKET);
                return new OrqlNode.ReqlArrayItem(name, schema, null, items, where);
            }
        } else if (isToken(TokenType.EOF)) {
            // 避免后续children null异常
            return new OrqlNode.ReqlObjectItem(name, schema, null, new ArrayList<>(), where);
        }
        throw new SyntaxException("miss object or array");
    }

    private List<OrqlNode.ReqlItem> visitItems(Schema schema) {
        List<OrqlNode.ReqlItem> items = new ArrayList<>();
        // * 位置
        Integer allPosition = -1;
        List<String> ignores = new ArrayList<>();
        while (true) {
            Boolean ignore = false;
            if (isToken(TokenType.NOT)) {
                walk();
                ignore = true;
            }
            OrqlNode.ReqlItem item = visitItem(schema);
            if (ignore) {
                ignores.add(item.getName());
            } else if (item instanceof OrqlNode.ReqlAllItem) {
                allPosition = items.size();
            } else {
                items.add(item);
            }
            // ,
            if (! isToken(TokenType.COMMA)) break;
            this.walk();
        }
        if (allPosition >= 0) {
            for (String columnName: schema.getColumnNames()) {
                if (ignores.contains(columnName)) continue;
                Column column = schema.getColumn(columnName);
                if (column.isRefKey()) continue;
                OrqlNode.ReqlColumnItem item = new OrqlNode.ReqlColumnItem(column);
                items.add(allPosition ++, item);
//                items.add(item);
            }
        }
        return items;
    }

    private OrqlNode.ReqlItem visitItem(Schema parent) {
        if (this.isToken(TokenType.ALL)) {
            this.walk();
            return new OrqlNode.ReqlAllItem();
        }
        String name = matchToken(TokenType.NAME);
        if (parent.containsColumn(name)) {
            Column column = parent.getColumn(name);
            return new OrqlNode.ReqlColumnItem(column);
        }
        if (parent.containsAssociation(name)) {
            Association association = parent.getAssociation(name);
            Schema ref = association.getRef();
            OrqlNode.ReqlWhere where = visitWhere(ref);
            List<OrqlNode.ReqlItem> items = new ArrayList<>();
            if (isToken(TokenType.COLON)) {
                // :
                walk();
                if (this.isToken(TokenType.OPEN_CURLY)) {
                    // {
                    walk();
                    items = this.visitItems(ref);
                    // }
                    this.matchToken(TokenType.CLOSE_CURLY);
                } else if (this.isToken(TokenType.OPEN_BRACKET)) {
                    // [
                    walk();
                    items = this.visitItems(ref);
                    // ]
                    this.matchToken(TokenType.CLOSE_BRACKET);
                } else {
                    throw new SyntaxException("expect { or [");
                }
            }
            if (association.getType() == Association.Type.BelongsTo || association.getType() == Association.Type.HasOne) {
                return new OrqlNode.ReqlObjectItem(name, ref, association, items, where);
            }
            return new OrqlNode.ReqlArrayItem(name, ref, association, items, where);
        }
        throw new SyntaxException("schema " + parent.getName() + " not exist column " + name);
    }

    private OrqlNode.ReqlWhere visitWhere(Schema schema) {
        OrqlNode.ReqlExp exp = null;
        List<OrqlNode.ReqlOrder> orders = null;
        if (isToken(TokenType.OPEN_PAREN) || isToken(TokenType.NAME)) {
            // 表达式以(或name开头
            exp = visitExp(schema);
        }
        if (this.isToken(TokenType.ORDER)) {
            walk();
            // order
            orders = visitOrders(schema);
        }
        return new OrqlNode.ReqlWhere(exp, orders);
    }

    // order a b c, d e f
    private List<OrqlNode.ReqlOrder> visitOrders(Schema schema) {
        List<OrqlNode.ReqlOrder> orders = new ArrayList<>();
        while (true) {
            OrqlNode.ReqlOrder order = visitOrder(schema);
            orders.add(order);
            // ,
            if (! this.isToken(TokenType.COMMA)) break;
            walk();
        }
        return orders;
    }

    private OrqlNode.ReqlOrder visitOrder(Schema schema) {
        List<Column> columns = new ArrayList<>();
        String sort = "asc";
        while (true) {
            String name = matchToken(TokenType.NAME);
            Column column = schema.getColumn(name);
            if (column == null) throw new SyntaxException("schema " + schema.getName() + " not exist column " + name);
            columns.add(column);
            if (isString("asc") || isString("desc")) {
                sort = token.getValue();
                this.walk();
                break;
            }
            if (! isToken(TokenType.NAME)) break;
        }
        return new OrqlNode.ReqlOrder(columns, sort);
    }

    private OrqlNode.ReqlExp visitExp(Schema schema) {
        OrqlNode.ReqlExp tmp = visitExpTerm(schema);
        while (isToken(TokenType.OR)) {
            // ||
            this.walk();
            OrqlNode.ReqlExp exp = visitExp(schema);
            tmp = new OrqlNode.ReqlOrExp(tmp, exp);
        }
        return tmp;
    }

    private OrqlNode.ReqlExp visitExpTerm(Schema schema) {
        OrqlNode.ReqlExp tmp = visitFactor(schema);
        while (isToken(TokenType.AND)) {
            // &&
            this.walk();
            OrqlNode.ReqlExp term = visitExpTerm(schema);
            tmp = new OrqlNode.ReqlAndExp(tmp, term);
        }
        return tmp;
    }

    private OrqlNode.ReqlExp visitFactor(Schema schema) {
        if (isToken(TokenType.OPEN_PAREN)) {
            // (
            walk();
            OrqlNode.ReqlExp exp = visitExp(schema);
            this.matchToken(TokenType.CLOSE_PAREN);
            return new OrqlNode.ReqlNestExp(exp);
        }
        Column column = visitColumn(schema);
        ExpOp op = visitOp();
        if (isToken(TokenType.NAME)) {
            Column right = visitColumn(schema);
            return new OrqlNode.ReqlColumnExp(column, op, right);
        }
        if (isToken(TokenType.PARAM)) {
            String param = matchToken(TokenType.PARAM);
            return new OrqlNode.ReqlColumnExp(column, op, param);
        }
        Object value = visitValue();
        return new OrqlNode.ReqlColumnExp(column, op, value);
    }

    private Object visitValue() {
        if (isToken(TokenType.INT)) {
            Integer value = Integer.valueOf(token.getValue());
            walk();
            return value;
        }
        if (isToken(TokenType.FLOAT)) {
            Float value = Float.valueOf(token.getValue());
            walk();
            return value;
        }
        if (isToken(TokenType.BOOLEAN)) {
            Boolean value = token.getValue().equals("true");
            this.walk();
            return value;
        }
        if (isToken(TokenType.STRING)) {
            String value = token.getValue();
            walk();
            return value;
        }
        if (isToken(TokenType.NULL)) {
            walk();
            return new OrqlNode.NullValue();
        }
        throw new SyntaxException("expect value");
    }

    private ExpOp visitOp() {
        if (isToken(TokenType.EQ)) {
            this.walk();
            return ExpOp.Eq;
        }
        if (isToken(TokenType.GT)) {
            this.walk();
            return ExpOp.Gt;
        }
        if (isToken(TokenType.GE)) {
            this.walk();
            return ExpOp.Ge;
        }
        if (isToken(TokenType.LT)) {
            this.walk();
            return ExpOp.Lt;
        }
        if (isToken(TokenType.LE)) {
            this.walk();
            return ExpOp.Le;
        }
        if (isToken(TokenType.LIKE)) {
            this.walk();
            return ExpOp.Like;
        }
        if (isToken(TokenType.NE)) {
            this.walk();
            return ExpOp.Ne;
        }
        throw new SyntaxException("expect op");
    }

    private Column visitColumn(Schema schema) {
        String name = matchToken(TokenType.NAME);
        Column column = schema.getColumn(name);
        if (column == null) throw new SyntaxException("schema " + schema.getName() + " not exist column " + name);
        return column;
    }

}
