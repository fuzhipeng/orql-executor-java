package com.github.orql.executor.sql;

import com.github.orql.executor.*;
import com.github.orql.executor.exception.SqlGenException;
import com.github.orql.executor.orql.OrqlNode;
import com.github.orql.executor.orql.OrqlNode.*;
import com.github.orql.executor.schema.*;
import com.github.orql.executor.sql.SqlNode.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class OrqlToSql {

    private Logger logger = LoggerFactory.getLogger(OrqlToSql.class);

    private SqlGenerator sqlGenerator = new SqlGenerator();
    
    private Map<OrqlRefItem, String> sqlCaches = new HashMap<>();

    /**
     * 查询包装类
     */
    private static class QueryWrapper {
        OrqlNode.OrqlRefItem item;
        String path;
        QueryWrapper(OrqlNode.OrqlRefItem item, String path) {
            this.item = item;
            this.path = path;
        }
    }
    
    public String toAdd(OrqlRefItem root) {
        if (sqlCaches.containsKey(root)) return sqlCaches.get(root);
        List<SqlColumn> columns = new ArrayList<>();
        List<SqlParam> params = new ArrayList<>();
        for (OrqlItem item : root.getChildren()) {
            if (item instanceof OrqlColumnItem) {
                Column columnItem = ((OrqlColumnItem) item).getColumn();
                columns.add(new SqlColumn(columnItem.getField()));
                params.add(new SqlParam(columnItem.getName()));
            } else if (item instanceof OrqlRefItem) {
                Association association = ((OrqlRefItem) item).getAssociation();
                switch (association.getType()) {
                    case BelongsTo:
                        columns.add(new SqlColumn(association.getRefKey()));
                        params.add(new SqlParam(association.getRefKey()));
                        break;
                }
            }
        }
        SqlInsert insert = new SqlInsert(root.getRef().getTable(), columns, params);
        String sql = sqlGenerator.gen(insert);
        sqlCaches.put(root, sql);
        return sql;
    }

    public String toDelete(OrqlRefItem root) {
        if (sqlCaches.containsKey(root)) return sqlCaches.get(root);
        SqlExp exp = genExp(root.getWhere().getExp(), root.getRef().getTable());
        SqlDelete delete = new SqlDelete(root.getRef().getTable(), exp);
        String sql = sqlGenerator.gen(delete);
        sqlCaches.put(root, sql);
        return sql;
    }

    public String toUpdate(OrqlRefItem root) {
        if (sqlCaches.containsKey(root)) return sqlCaches.get(root);
        SqlExp exp = genExp(root.getWhere().getExp(), root.getRef().getTable());
        List<SqlColumn> sets = new ArrayList<>();
        for (OrqlItem item : root.getChildren()) {
            if (item instanceof OrqlColumnItem) {
                sets.add(new SqlColumn(((OrqlColumnItem) item).getColumn().getField()));
            } else if (item instanceof OrqlRefItem) {
                Association association = ((OrqlRefItem) item).getAssociation();
                switch (association.getType()) {
                    case BelongsTo:
                        // user belongsTo role
                        // roleId = #role.id
                        sets.add(new SqlColumn(((OrqlRefItem) item).getAssociation().getRefKey()));
                        break;
                }
            }
        }
        SqlUpdate update = new SqlUpdate(root.getRef().getTable(), exp, sets);
        String sql = sqlGenerator.gen(update);
        sqlCaches.put(root, sql);
        return sql;
    }

    public String toQuery(OrqlOp op, OrqlRefItem root, SqlPage sqlPage) {
        sqlPage = sqlPage == null ? new SqlPage(null, null) : sqlPage;
        Schema rootSchema = root.getRef();
        String table = rootSchema.getTable();
        List<SqlJoin> joins = new ArrayList<>();
        List<SqlExp> where = new ArrayList<>();
        //根节点exp
        SqlExp rootExp = null;
        List<SqlColumn> select = new ArrayList<>();
        // 排序
        List<SqlOrder> orders = new ArrayList<>();
        // 根节点排序
        List<SqlOrder> rootOrders = new ArrayList<>();
        Stack<QueryWrapper> queryStack = new Stack<>();
        queryStack.push(new QueryWrapper(root, table));
        //存在数组类型关联
        boolean hasArrayRef = false;

        while (! queryStack.isEmpty()) {
            QueryWrapper queryWrapper = queryStack.pop();
            OrqlRefItem currentItem = queryWrapper.item;
            String currentPath = queryWrapper.path;
            Schema currentSchema = currentItem.getRef();
            Column idColumn = currentSchema.getIdColumn();
            // 是否有主键
            boolean hasId = false;
            // 是否有select
            boolean hasSelect = false;
            if (currentItem.getWhere() != null) {
                if (currentItem.getWhere().getExp() != null) {
                    SqlExp exp = genExp(currentItem.getWhere().getExp(), currentPath);
                    if (currentPath.equals(table)) {
                        // root where
                        rootExp = exp;
                    } else {
                        where.add(exp);
                    }
                }
                if (currentItem.getWhere().getOrders() != null) {
                    // 添加排序
                    for (OrqlOrder reqlOrder : currentItem.getWhere().getOrders()) {
                        List<SqlColumn> columns = new ArrayList<>();
                        for (Column column : reqlOrder.getColumns()) {
                            columns.add(new SqlColumn(column.getField(), currentPath));
                        }
                        SqlOrder sqlOrder = new SqlOrder(columns, reqlOrder.getSort());
                        // 分开存
                        if (currentPath.equals(table)) {
                            rootOrders.add(sqlOrder);
                        }
                        // 嵌套内外都要order
                        orders.add(sqlOrder);
                    }
                }
            }
            for (OrqlItem child : currentItem.getChildren()) {
                hasSelect = true;
                if (child instanceof OrqlRefItem) {
                    Association association = currentSchema.getAssociation(child.getName());

                    if (association.getType() == Association.Type.HasMany || association.getType() == Association.Type.BelongsToMany) {
                       if (! ((OrqlRefItem) child).getChildren().isEmpty()) {
                           //存在数组类型关联
                           hasArrayRef = true;
                       }
                    }
                    Schema childSchema = ((OrqlRefItem) child).getRef();
                    Column childIdColumn = childSchema.getIdColumn();
                    String childPath = currentPath + Constants.SqlSplit + child.getName();
                    //入栈
                    queryStack.push(new QueryWrapper((OrqlRefItem) child, childPath));
                    SqlJoinType joinType = association.isRequired() ? SqlJoinType.Inner : SqlJoinType.Left;
                    Association.Type type = association.getType();
                    if (type == Association.Type.HasMany) {
                        // role hasMany user
                        // user.roleId = role.id
                        SqlExp on = new SqlColumnExp(
                                new SqlColumn(association.getRefKey(), childPath),
                                ExpOp.Eq,
                                new SqlColumn(childIdColumn.getField(), currentPath));
                        joins.add(new SqlJoin(childSchema.getTable(), childPath, joinType, on));
                    } else if (type == Association.Type.HasOne) {
                        // user hasOne info
                        // info.userId = user.id
                        SqlExp on = new SqlColumnExp(
                                new SqlColumn(association.getRefKey(), childPath),
                                ExpOp.Eq,
                                new SqlColumn(childIdColumn.getField(), currentPath));
                        joins.add(new SqlJoin(childSchema.getTable(), childPath, joinType, on));
                    } else if (type == Association.Type.BelongsTo) {
                        // user belongsTo role
                        // role.id = user.roleId
                        SqlExp on = new SqlColumnExp(
                                new SqlColumn(association.getRefId().getField(), childPath),
                                ExpOp.Eq,
                                new SqlColumn(association.getRefKey(), currentPath));
                        joins.add(new SqlJoin(childSchema.getTable(), childPath, joinType, on));
                    } else if (type == Association.Type.BelongsToMany) {
                        // post belongsToMany tag, middle postTags
                        // postTags.postId = post.id
                        // postTags.tagId = tag.id
                        Schema targetSchema = association.getCurrent();
                        Schema foreign = association.getRef();
                        String middlePath = childPath + Constants.SqlSplit + association.getMiddle();
                        SqlExp leftOn = new SqlColumnExp(
                                new SqlColumn(association.getMiddleKey(), middlePath),
                                ExpOp.Eq,
                                new SqlColumn(childIdColumn.getField(), currentPath));
                        joins.add(new SqlJoin(association.getMiddle(), middlePath, joinType, leftOn));
                        SqlExp rightOn = new SqlColumnExp(
                                new SqlColumn(targetSchema.getIdColumn().getField(), childPath),
                                ExpOp.Eq,
                                new SqlColumn(association.getRefMiddleKey(), middlePath));
                        joins.add(new SqlJoin(foreign.getTable(), childPath, joinType, rightOn));
                    }
                } else {
                    if (child.getName().equals(idColumn.getName())) {
                        hasId = true;
                    }
                    if (op != OrqlOp.Count) {
                        if (child instanceof OrqlColumnItem) {
                            OrqlColumnItem columnItem = (OrqlColumnItem) child;
                            select.add(new SqlColumn(columnItem.getColumn().getField(), currentPath));
                        } else {
                            select.add(new SqlColumn(child.getName(), currentPath));
                        }
                    }
                }
            }
            if (! hasId) {
                if (op != OrqlOp.Count && hasSelect) {
                    //插入id
                    select.add(new SqlColumn(idColumn.getField(), currentPath));
                }
            }
        }
        //FIXME 逻辑太乱，后续修复
        SqlQuery query;
        if (op == OrqlOp.Count) {
            //分页
            select.add(new SqlCountColumn(rootSchema.getIdField(), table));
            if (rootExp != null) where.add(0, rootExp);
            SqlForm from = new SqlTableForm(new SqlTable(table, table));
            query = new SqlQuery(select, from, where, joins, orders, sqlPage);
        } else if (hasArrayRef && sqlPage.getLimit() != null) {
            //嵌套分页查询
            List<SqlColumn> innerSelect = Collections.singletonList(new SqlColumn("*"));
            List<SqlExp> innerWhere = rootExp != null ? Collections.singletonList(rootExp) : new ArrayList<>();
            SqlTableForm innerFrom = new SqlTableForm(new SqlTable(table));
            SqlForm from = new SqlInnerFrom(new SqlQuery(innerSelect, innerFrom, innerWhere, new ArrayList<>(), rootOrders, sqlPage));
            query = new SqlQuery(select, from, where, joins, orders,  null);
        } else if (! hasArrayRef && sqlPage.getLimit() == null && root instanceof OrqlObjectItem) {
            //无分页，单个查询，而且没有数组类型关联查询
            if (rootExp != null) where.add(0, rootExp);
            SqlForm from = new SqlTableForm(new SqlTable(table, table));
            sqlPage = new SqlPage(null, 1);
            query = new SqlQuery(select, from, where, joins, orders, sqlPage);
        } else {
            if (rootExp != null) where.add(0, rootExp);
            SqlForm from = new SqlTableForm(new SqlTable(table, table));
            query = new SqlQuery(select, from, where, joins, orders, sqlPage);
        }
        return sqlGenerator.gen(query);
    }

    private SqlExp genExp(OrqlExp reqlExp, String path) {
        if (reqlExp instanceof OrqlAndExp) {
            return new SqlAndExp(
                    genExp(((OrqlAndExp) reqlExp).getLeft(), path),
                    genExp(((OrqlAndExp) reqlExp).getRight(), path));
        }
        if (reqlExp instanceof OrqlOrExp) {
            return new SqlOrExp(
                    genExp(((OrqlOrExp) reqlExp).getLeft(), path),
                    genExp(((OrqlOrExp) reqlExp).getRight(), path));
        }
        if (reqlExp instanceof OrqlNestExp) {
            return new SqlNestExp(genExp(((OrqlNestExp) reqlExp).getExp(), path));
        }
        if (reqlExp instanceof OrqlColumnExp) {
            return genExpColumn((OrqlColumnExp) reqlExp, path);
        }
        throw new SqlGenException();
    }

    private SqlExp genExpColumn(OrqlColumnExp reqlColumnExp, String path) {
        SqlColumn left = new SqlColumn(reqlColumnExp.getLeft().getField(), path);
        if (reqlColumnExp.getRightColumn() != null) {
            SqlColumn right = new SqlColumn(reqlColumnExp.getRightColumn().getField(), path);
            return new SqlColumnExp(left, reqlColumnExp.getOp(), right);
        }
        if (reqlColumnExp.getRightParam() != null) {
            SqlParam right = new SqlParam(reqlColumnExp.getRightParam());
            return new SqlColumnExp(left, reqlColumnExp.getOp(), right);
        }
        Object right = reqlColumnExp.getRightValue();
        return new SqlColumnExp(left, reqlColumnExp.getOp(), right);
    }
}