package org.umlg.sqlg.sql.dialect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.mchange.v2.c3p0.C3P0ProxyConnection;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.postgis.Geometry;
import org.postgis.PGgeometry;
import org.postgis.Point;
import org.postgis.Polygon;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import org.postgresql.copy.PGCopyInputStream;
import org.postgresql.copy.PGCopyOutputStream;
import org.postgresql.core.BaseConnection;
import org.postgresql.jdbc4.Jdbc4Connection;
import org.postgresql.util.PGobject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.umlg.sqlg.gis.GeographyPoint;
import org.umlg.sqlg.gis.GeographyPolygon;
import org.umlg.sqlg.gis.Gis;
import org.umlg.sqlg.structure.*;

import java.io.*;
import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * Date: 2014/07/16
 * Time: 1:42 PM
 */
public class PostgresDialect extends BaseSqlDialect implements SqlDialect {

    private static final String BATCH_NULL = "";
    private static final String COPY_COMMAND_DELIMITER = "\t";
    //this strange character is apparently an illegal json char so its good as a quote
    private static final String COPY_COMMAND_QUOTE = "e'\\x01'";
    private static final int PARAMETER_LIMIT = 32767;
    private Logger logger = LoggerFactory.getLogger(SqlgGraph.class.getName());

    public PostgresDialect(Configuration configurator) {
        super(configurator);
    }

    @Override
    public boolean supportsBatchMode() {
        return true;
    }

    @Override
    public Set<String> getDefaultSchemas() {
        return new HashSet<>(Arrays.asList("pg_catalog", "public", "information_schema"));
    }

    @Override
    public String getJdbcDriver() {
        return "org.postgresql.xa.PGXADataSource";
    }

    @Override
    public String getForeignKeyTypeDefinition() {
        return "BIGINT";
    }

    @Override
    public String getColumnEscapeKey() {
        return "\"";
    }

    @Override
    public String getPrimaryKeyType() {
        return "BIGINT NOT NULL PRIMARY KEY";
    }

    @Override
    public String getAutoIncrementPrimaryKeyConstruct() {
        return "SERIAL PRIMARY KEY";
    }

    public void assertTableName(String tableName) {
        if (!StringUtils.isEmpty(tableName) && tableName.length() > 63) {
            throw new IllegalStateException(String.format("Postgres table names must be 63 characters or less! Given table name is %s", new String[]{tableName}));
        }
    }

    @Override
    public String getArrayDriverType(PropertyType propertyType) {
        switch (propertyType) {
            case BOOLEAN_ARRAY:
                return "bool";
            case SHORT_ARRAY:
                return "smallint";
            case INTEGER_ARRAY:
                return "integer";
            case LONG_ARRAY:
                return "bigint";
            case FLOAT_ARRAY:
                return "float";
            case DOUBLE_ARRAY:
                return "float";
            case STRING_ARRAY:
                return "varchar";
            default:
                throw new IllegalStateException("propertyType " + propertyType.name() + " unknown!");
        }
    }

    @Override
    public String existIndexQuery(SchemaTable schemaTable, String prefix, String indexName) {
        StringBuilder sb = new StringBuilder("SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace");
        sb.append(" WHERE  c.relname = '");
        sb.append(indexName);
        sb.append("' AND n.nspname = '");
        sb.append(schemaTable.getSchema());
        sb.append("'");
        return sb.toString();
    }

    /**
     * flushes the cache via the copy command.
     * first writes the
     *
     * @param vertexCache A rather complex object.
     *                    The map's key is the vertex being cached.
     *                    The Triple holds,
     *                    1) The in labels
     *                    2) The out labels
     *                    3) The properties as a map of key values
     */
    @Override
    public Map<SchemaTable, Pair<Long, Long>> flushVertexCache(SqlgGraph sqlgGraph, Map<SchemaTable, Pair<SortedSet<String>, Map<SqlgVertex, Map<String, Object>>>> vertexCache) {
        Map<SchemaTable, Pair<Long, Long>> verticesRanges = new LinkedHashMap<>();
        C3P0ProxyConnection con = (C3P0ProxyConnection) sqlgGraph.tx().getConnection();
        try {
            Method m = BaseConnection.class.getMethod("getCopyAPI", new Class[]{});
            Object[] arg = new Object[]{};
            CopyManager copyManager = (CopyManager) con.rawConnectionOperation(m, C3P0ProxyConnection.RAW_CONNECTION, arg);
            for (SchemaTable schemaTable : vertexCache.keySet()) {
                Pair<SortedSet<String>, Map<SqlgVertex, Map<String, Object>>> vertices = vertexCache.get(schemaTable);
                //insert the labeled vertices
                long endHigh;
                long numberInserted;
                try (InputStream is = mapToLabeledVertex_InputStream(vertices)) {
                    StringBuffer sql = new StringBuffer();
                    sql.append("COPY ");
                    sql.append(maybeWrapInQoutes(schemaTable.getSchema()));
                    sql.append(".");
                    sql.append(maybeWrapInQoutes(SchemaManager.VERTEX_PREFIX + schemaTable.getTable()));
                    sql.append(" (");
                    if (vertices.getLeft().isEmpty()) {
                        //copy command needs at least one field.
                        //check if the dummy field exist, if not create it
                        sqlgGraph.getSchemaManager().ensureColumnExist(
                                schemaTable.getSchema(),
                                SchemaManager.VERTEX_PREFIX + schemaTable.getTable(),
                                ImmutablePair.of("_copy_dummy", PropertyType.from(0)));
                        sql.append(maybeWrapInQoutes("_copy_dummy"));
                    } else {
                        int count = 1;
                        for (String key : vertices.getLeft()) {
                            if (count > 1 && count <= vertices.getLeft().size()) {
                                sql.append(", ");
                            }
                            count++;
                            sql.append(maybeWrapInQoutes(key));
                        }
                    }
                    sql.append(")");

                    sql.append(" FROM stdin CSV DELIMITER '");
                    sql.append(COPY_COMMAND_DELIMITER);
                    sql.append("' ");
                    sql.append("QUOTE ");
                    sql.append(COPY_COMMAND_QUOTE);
                    sql.append(";");
                    if (logger.isDebugEnabled()) {
                        logger.debug(sql.toString());
                    }
                    numberInserted = copyManager.copyIn(sql.toString(), is);
                    try (PreparedStatement preparedStatement = con.prepareStatement("SELECT CURRVAL('\"" + schemaTable.getSchema() + "\".\"" + SchemaManager.VERTEX_PREFIX + schemaTable.getTable() + "_ID_seq\"');")) {
                        ResultSet resultSet = preparedStatement.executeQuery();
                        resultSet.next();
                        endHigh = resultSet.getLong(1);
                        resultSet.close();
                    }
                    //set the id on the vertex
                    long id = endHigh - numberInserted + 1;
                    for (SqlgVertex sqlgVertex : vertices.getRight().keySet()) {
                        sqlgVertex.setInternalPrimaryKey(RecordId.from(schemaTable, id++));
                    }
                }
                verticesRanges.put(schemaTable, Pair.of(endHigh - numberInserted + 1, endHigh));
            }
            return verticesRanges;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void flushEdgeCache(SqlgGraph sqlgGraph, Map<SchemaTable, Pair<SortedSet<String>, Map<SqlgEdge, Triple<SqlgVertex, SqlgVertex, Map<String, Object>>>>> edgeCache) {
        C3P0ProxyConnection con = (C3P0ProxyConnection) sqlgGraph.tx().getConnection();
        try {
            Method m = BaseConnection.class.getMethod("getCopyAPI", new Class[]{});
            Object[] arg = new Object[]{};
            CopyManager copyManager = (CopyManager) con.rawConnectionOperation(m, C3P0ProxyConnection.RAW_CONNECTION, arg);

            for (SchemaTable schemaTable : edgeCache.keySet()) {
                Pair<SortedSet<String>, Map<SqlgEdge, Triple<SqlgVertex, SqlgVertex, Map<String, Object>>>> triples = edgeCache.get(schemaTable);
                try (InputStream is = mapToEdge_InputStream(triples)) {
                    StringBuffer sql = new StringBuffer();
                    sql.append("COPY ");
                    sql.append(maybeWrapInQoutes(schemaTable.getSchema()));
                    sql.append(".");
                    sql.append(maybeWrapInQoutes(SchemaManager.EDGE_PREFIX + schemaTable.getTable()));
                    sql.append(" (");
                    for (Triple<SqlgVertex, SqlgVertex, Map<String, Object>> triple : triples.getRight().values()) {
                        int count = 1;
                        sql.append(maybeWrapInQoutes(triple.getLeft().getSchema() + "." + triple.getLeft().getTable() + SchemaManager.OUT_VERTEX_COLUMN_END));
                        sql.append(", ");
                        sql.append(maybeWrapInQoutes(triple.getMiddle().getSchema() + "." + triple.getMiddle().getTable() + SchemaManager.IN_VERTEX_COLUMN_END));
                        for (String key : triples.getLeft()) {
                            if (count <= triples.getLeft().size()) {
                                sql.append(", ");
                            }
                            count++;
                            sql.append(maybeWrapInQoutes(key));
                        }
                        break;
                    }
                    sql.append(") ");

                    sql.append(" FROM stdin CSV DELIMITER '");
                    sql.append(COPY_COMMAND_DELIMITER);
                    sql.append("' ");
                    sql.append("QUOTE ");
                    sql.append(COPY_COMMAND_QUOTE);
                    sql.append(";");
                    if (logger.isDebugEnabled()) {
                        logger.debug(sql.toString());
                    }
                    copyManager.copyIn(sql.toString(), is);
                }
            }


        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void flushVertexLabelCache(SqlgGraph sqlgGraph, Map<SqlgVertex, Pair<String, String>> vertexOutInLabelMap) {
        if (!vertexOutInLabelMap.isEmpty()) {
            Connection conn = sqlgGraph.tx().getConnection();
            StringBuilder sql = new StringBuilder();
            sql.append("UPDATE \"VERTICES\" a\n" +
                    "SET (\"VERTEX_SCHEMA\", \"VERTEX_TABLE\", \"IN_LABELS\", \"OUT_LABELS\") =\n" +
                    "\t(v.\"VERTEX_SCHEMA\", v.\"VERTEX_TABLE\", v.\"IN_LABELS\", v.\"OUT_LABELS\")\n" +
                    "FROM ( \n" +
                    "    VALUES \n");
            int count = 1;
            for (SqlgVertex sqlgVertex : vertexOutInLabelMap.keySet()) {
                Pair<String, String> outInLabel = vertexOutInLabelMap.get(sqlgVertex);
                sql.append("        (");
                sql.append(sqlgVertex.id());
                sql.append(", '");
                sql.append(sqlgVertex.getSchema());
                sql.append("', '");
                sql.append(sqlgVertex.getTable());
                sql.append("', ");
                if (outInLabel.getRight() == null) {
                    sql.append("null");
                } else {
                    sql.append("'");
                    sql.append(outInLabel.getRight());
                    sql.append("'");
                }
                sql.append(", ");

                if (outInLabel.getLeft() == null) {
                    sql.append("null");
                } else {
                    sql.append("'");
                    sql.append(outInLabel.getLeft());
                    sql.append("'");
                }
                sql.append(")");
                if (count++ < vertexOutInLabelMap.size()) {
                    sql.append(",        \n");
                }
            }
            sql.append("\n) AS v(id, \"VERTEX_SCHEMA\", \"VERTEX_TABLE\", \"IN_LABELS\", \"OUT_LABELS\")");
            sql.append("\nWHERE a.\"" + SchemaManager.ID + "\" = v.id");
            if (logger.isDebugEnabled()) {
                logger.debug(sql.toString());
            }
            try (Statement statement = conn.createStatement()) {
                statement.execute(sql.toString());
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void flushVertexPropertyCache(SqlgGraph sqlgGraph, Map<SchemaTable, Pair<SortedSet<String>, Map<SqlgVertex, Map<String, Object>>>> schemaVertexPropertyCache) {

        Connection conn = sqlgGraph.tx().getConnection();
        for (SchemaTable schemaTable : schemaVertexPropertyCache.keySet()) {

            Pair<SortedSet<String>, Map<SqlgVertex, Map<String, Object>>> vertexKeysPropertyCache = schemaVertexPropertyCache.get(schemaTable);
            SortedSet<String> keys = vertexKeysPropertyCache.getLeft();
            Map<SqlgVertex, Map<String, Object>> vertexPropertyCache = vertexKeysPropertyCache.getRight();


            StringBuilder sql = new StringBuilder();
            sql.append("UPDATE ");
            sql.append(maybeWrapInQoutes(schemaTable.getSchema()));
            sql.append(".");
            sql.append(maybeWrapInQoutes(SchemaManager.VERTEX_PREFIX + schemaTable.getTable()));
            sql.append(" a \nSET\n\t(");
            int count = 1;
            for (String key : keys) {
                sql.append(maybeWrapInQoutes(key));
                if (count++ < keys.size()) {
                    sql.append(", ");
                }
            }
            sql.append(") = \n\t(");
            count = 1;
            for (String key : keys) {
                sql.append("v.");
                sql.append(maybeWrapInQoutes(key));
                if (count++ < keys.size()) {
                    sql.append(", ");
                }
            }
            sql.append(")\nFROM (\nVALUES\n\t");
            count = 1;
            for (SqlgVertex sqlgVertex : vertexPropertyCache.keySet()) {
                Map<String, Object> properties = vertexPropertyCache.get(sqlgVertex);
                sql.append("(");
                sql.append(((RecordId) sqlgVertex.id()).getId());
                sql.append(", ");
                int countProperties = 1;
                for (String key : keys) {
                    Object value = properties.get(key);
                    if (value != null) {
                        PropertyType propertyType = PropertyType.from(value);
                        switch (propertyType) {
                            case BOOLEAN:
                                sql.append(value);
                                break;
                            case BYTE:
                                sql.append(value);
                                break;
                            case SHORT:
                                sql.append(value);
                                break;
                            case INTEGER:
                                sql.append(value);
                                break;
                            case LONG:
                                sql.append(value);
                                break;
                            case FLOAT:
                                sql.append(value);
                                break;
                            case DOUBLE:
                                sql.append(value);
                                break;
                            case STRING:
                                //Postgres supports custom quoted strings using the 'with token' clause
                                sql.append("$token$");
                                sql.append(value);
                                sql.append("$token$");
                                break;
                            case LOCALDATETIME:
                                sql.append("'");
                                sql.append(value.toString());
                                sql.append("'::TIMESTAMP");
                                break;
                            case LOCALDATE:
                                sql.append("'");
                                sql.append(value.toString());
                                sql.append("'::DATE");
                                break;
                            case LOCALTIME:
                                sql.append("'");
                                sql.append(value.toString());
                                sql.append("'::TIME");
                                break;
                            case JSON:
                                sql.append("'");
                                sql.append(value.toString());
                                sql.append("'::JSONB");
                                break;
                            case BOOLEAN_ARRAY:
                                break;
                            case BYTE_ARRAY:
                                break;
                            case SHORT_ARRAY:
                                break;
                            case INTEGER_ARRAY:
                                break;
                            case LONG_ARRAY:
                                break;
                            case FLOAT_ARRAY:
                                break;
                            case DOUBLE_ARRAY:
                                break;
                            case STRING_ARRAY:
                                break;
                            default:
                                throw new IllegalStateException("Unknown propertyType " + propertyType.name());
                        }
                    } else {
                        //set it to what it is
                        if (sqlgVertex.property(key).isPresent()) {
                            sql.append("$token$");
                            sql.append((Object) sqlgVertex.value(key));
                            sql.append("$token$");

                        } else {
                            sql.append("null");
                        }
                    }
                    if (countProperties++ < keys.size()) {
                        sql.append(", ");
                    }
                }
                sql.append(")");
                if (count++ < vertexPropertyCache.size()) {
                    sql.append(",\n\t");
                }
            }
            sql.append("\n) AS v(id, ");
            count = 1;
            for (String key : keys) {
                sql.append(maybeWrapInQoutes(key));
                if (count++ < keys.size()) {
                    sql.append(", ");
                }
            }
            sql.append(")");
            sql.append("\nWHERE a.\"" + SchemaManager.ID + "\" = v.id");
            if (logger.isDebugEnabled()) {
                logger.debug(sql.toString());
            }
            try (Statement statement = conn.createStatement()) {
                statement.execute(sql.toString());
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public String constructCompleteCopyCommandSqlVertex(SqlgGraph sqlgGraph, SqlgVertex vertex, Map<String, Object> keyValueMap) {
        StringBuffer sql = new StringBuffer();
        sql.append("COPY ");
        sql.append(maybeWrapInQoutes(vertex.getSchema()));
        sql.append(".");
        sql.append(maybeWrapInQoutes(SchemaManager.VERTEX_PREFIX + vertex.getTable()));
        sql.append(" (");
        if (keyValueMap.isEmpty()) {
            //copy command needs at least one field.
            //check if the dummy field exist, if not create it
            sqlgGraph.getSchemaManager().ensureColumnExist(
                    vertex.getSchema(),
                    SchemaManager.VERTEX_PREFIX + vertex.getTable(),
                    ImmutablePair.of("_copy_dummy", PropertyType.from(0)));
            sql.append(maybeWrapInQoutes("_copy_dummy"));
        } else {
            int count = 1;
            for (String key : keyValueMap.keySet()) {
                if (count > 1 && count <= keyValueMap.size()) {
                    sql.append(", ");
                }
                count++;
                sql.append(maybeWrapInQoutes(key));
            }
        }
        sql.append(")");
        sql.append(" FROM stdin CSV DELIMITER '");
        sql.append(COPY_COMMAND_DELIMITER);
        sql.append("' ");
        sql.append("QUOTE ");
        sql.append(COPY_COMMAND_QUOTE);
        sql.append(";");
        if (logger.isDebugEnabled()) {
            logger.debug(sql.toString());
        }
        return sql.toString();
    }

    @Override
    public String temporaryTableCopyCommandSqlVertex(SqlgGraph sqlgGraph, SchemaTable schemaTable, Map<String, Object> keyValueMap) {
        StringBuffer sql = new StringBuffer();
        sql.append("COPY ");
        //Temp tables only
        sql.append(maybeWrapInQoutes(SchemaManager.VERTEX_PREFIX + schemaTable.getTable()));
        sql.append(" (");
        if (keyValueMap.isEmpty()) {
            //copy command needs at least one field.
            //check if the dummy field exist, if not create it
            sqlgGraph.getSchemaManager().ensureColumnExist(
                    schemaTable.getSchema(),
                    SchemaManager.VERTEX_PREFIX + schemaTable.getTable(),
                    ImmutablePair.of("_copy_dummy", PropertyType.from(0)));
            sql.append(maybeWrapInQoutes("_copy_dummy"));
        } else {
            int count = 1;
            for (String key : keyValueMap.keySet()) {
                if (count > 1 && count <= keyValueMap.size()) {
                    sql.append(", ");
                }
                count++;
                sql.append(maybeWrapInQoutes(key));
            }
        }
        sql.append(")");
        sql.append(" FROM stdin CSV DELIMITER '");
        sql.append(COPY_COMMAND_DELIMITER);
        sql.append("' ");
        sql.append("QUOTE ");
        sql.append(COPY_COMMAND_QUOTE);
        sql.append(";");
        if (logger.isDebugEnabled()) {
            logger.debug(sql.toString());
        }
        return sql.toString();
    }

    @Override
    public void writeStreamingVertex(OutputStream out, Map<String, Object> keyValueMap) {
        try {
            int countKeys = 1;
            if (keyValueMap.isEmpty()) {
                out.write(Integer.toString(1).getBytes());
            } else {
                for (Map.Entry<String, Object> entry : keyValueMap.entrySet()) {
                    if (countKeys > 1 && countKeys <= keyValueMap.size()) {
                        out.write(COPY_COMMAND_DELIMITER.getBytes());
                    }
                    countKeys++;
                    Object value = entry.getValue();
                    if (value == null) {
                        out.write(getBatchNull().getBytes());
                    } else {
                        out.write(value.toString().getBytes());
                    }
                }
            }
            out.write("\n".getBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void writeCompleteEdge(OutputStream out, SqlgEdge sqlgEdge, SqlgVertex outVertex, SqlgVertex inVertex, Map<String, Object> keyValueMap) {
        try {
            String encoding = "UTF-8";
            out.write(((RecordId) outVertex.id()).getId().toString().getBytes(encoding));
            out.write(COPY_COMMAND_DELIMITER.getBytes(encoding));
            out.write(((RecordId) inVertex.id()).getId().toString().getBytes(encoding));
            for (Map.Entry<String, Object> entry : keyValueMap.entrySet()) {
                out.write(COPY_COMMAND_DELIMITER.getBytes(encoding));
                Object value = entry.getValue();
                if (value == null) {
                    out.write(getBatchNull().getBytes(encoding));
                } else {
                    out.write(value.toString().getBytes(encoding));
                }
            }
            out.write("\n".getBytes(encoding));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String constructCompleteCopyCommandSqlEdge(SqlgGraph sqlgGraph, SqlgEdge sqlgEdge, SqlgVertex outVertex, SqlgVertex inVertex, Map<String, Object> keyValueMap) {
        StringBuffer sql = new StringBuffer();
        sql.append("COPY ");
        sql.append(maybeWrapInQoutes(sqlgEdge.getSchema()));
        sql.append(".");
        sql.append(maybeWrapInQoutes(SchemaManager.EDGE_PREFIX + sqlgEdge.getTable()));
        sql.append(" (");
        sql.append(maybeWrapInQoutes(outVertex.getSchema() + "." + outVertex.getTable() + SchemaManager.OUT_VERTEX_COLUMN_END));
        sql.append(", ");
        sql.append(maybeWrapInQoutes(inVertex.getSchema() + "." + inVertex.getTable() + SchemaManager.IN_VERTEX_COLUMN_END));
        int count = 1;
        for (String key : keyValueMap.keySet()) {
            if (count <= keyValueMap.size()) {
                sql.append(", ");
            }
            count++;
            sql.append(maybeWrapInQoutes(key));
        }
        sql.append(") ");

        sql.append(" FROM stdin CSV DELIMITER '");
        sql.append(COPY_COMMAND_DELIMITER);
        sql.append("' ");
        sql.append("QUOTE ");
        sql.append(COPY_COMMAND_QUOTE);
        sql.append(";");
        if (logger.isDebugEnabled()) {
            logger.debug(sql.toString());
        }
        return sql.toString();
    }


    @Override
    public void flushEdgePropertyCache(SqlgGraph sqlgGraph, Map<SchemaTable, Pair<SortedSet<String>, Map<SqlgEdge, Map<String, Object>>>> edgePropertyCache) {

    }

    @Override
    public void flushRemovedVertices(SqlgGraph sqlgGraph, Map<SchemaTable, List<SqlgVertex>> removeVertexCache) {

        if (!removeVertexCache.isEmpty()) {


            //split the list of vertices, postgres has a 2 byte limit in the in clause
            for (Map.Entry<SchemaTable, List<SqlgVertex>> schemaVertices : removeVertexCache.entrySet()) {

                SchemaTable schemaTable = schemaVertices.getKey();

                Pair<Set<SchemaTable>, Set<SchemaTable>> tableLabels = sqlgGraph.getSchemaManager().getTableLabels(SchemaTable.of(schemaTable.getSchema(), SchemaManager.VERTEX_PREFIX + schemaTable.getTable()));

                //This is causing dead locks under load
//                dropForeignKeys(sqlgGraph, schemaTable);

                List<SqlgVertex> vertices = schemaVertices.getValue();
                int numberOfLoops = (vertices.size() / PARAMETER_LIMIT);
                int previous = 0;
                for (int i = 1; i <= numberOfLoops + 1; i++) {

                    int subListTo = i * PARAMETER_LIMIT;
                    List<SqlgVertex> subVertices;
                    if (i <= numberOfLoops) {
                        subVertices = vertices.subList(previous, subListTo);
                    } else {
                        subVertices = vertices.subList(previous, vertices.size());
                    }

                    previous = subListTo;

                    if (!subVertices.isEmpty()) {

                        Set<SchemaTable> inLabels = tableLabels.getLeft();
                        Set<SchemaTable> outLabels = tableLabels.getRight();

                        deleteEdges(sqlgGraph, schemaTable, subVertices, inLabels, true);
                        deleteEdges(sqlgGraph, schemaTable, subVertices, outLabels, false);

//                        Pair<Set<Long>, Set<SchemaTable>> outLabels = Pair.of(new HashSet<>(), new HashSet<>());
//                        Pair<Set<Long>, Set<SchemaTable>> inLabels = Pair.of(new HashSet<>(), new HashSet<>());
                        //get all the in and out labels for each vertex
                        //then for all in and out edges
                        //then remove the edges
//                        getInAndOutEdgesToRemove(sqlgGraph, subVertices, outLabels, inLabels);
//                        deleteEdges(sqlgGraph, schemaTable, outLabels, true);
//                        deleteEdges(sqlgGraph, schemaTable, inLabels, false);

                        StringBuilder sql = new StringBuilder("DELETE FROM ");
                        sql.append(sqlgGraph.getSchemaManager().getSqlDialect().maybeWrapInQoutes(schemaTable.getSchema()));
                        sql.append(".");
                        sql.append(sqlgGraph.getSchemaManager().getSqlDialect().maybeWrapInQoutes((SchemaManager.VERTEX_PREFIX) + schemaTable.getTable()));
                        sql.append(" WHERE ");
                        sql.append(sqlgGraph.getSchemaManager().getSqlDialect().maybeWrapInQoutes(SchemaManager.ID));
                        sql.append(" in (");
                        int count = 1;
                        for (SqlgVertex sqlgVertex : subVertices) {
                            sql.append("?");
                            if (count++ < subVertices.size()) {
                                sql.append(",");
                            }
                        }
                        sql.append(")");
                        if (sqlgGraph.getSqlDialect().needsSemicolon()) {
                            sql.append(";");
                        }
                        if (logger.isDebugEnabled()) {
                            logger.debug(sql.toString());
                        }
                        Connection conn = sqlgGraph.tx().getConnection();
                        try (PreparedStatement preparedStatement = conn.prepareStatement(sql.toString())) {
                            count = 1;
                            for (SqlgVertex sqlgVertex : subVertices) {
                                preparedStatement.setLong(count++, ((RecordId) sqlgVertex.id()).getId());
                            }
                            preparedStatement.executeUpdate();
                        } catch (SQLException e) {
                            throw new RuntimeException(e);
                        }

//                        sql = new StringBuilder("DELETE FROM ");
//                        sql.append(sqlgGraph.getSqlDialect().maybeWrapInQoutes(sqlgGraph.getSqlDialect().getPublicSchema()));
//                        sql.append(".");
//                        sql.append(sqlgGraph.getSqlDialect().maybeWrapInQoutes(SchemaManager.VERTICES));
//                        sql.append(" WHERE ");
//                        sql.append(sqlgGraph.getSqlDialect().maybeWrapInQoutes(SchemaManager.ID));
//                        sql.append(" in (");
//
//                        count = 1;
//                        for (SqlgVertex vertex : subVertices) {
//                            sql.append("?");
//                            if (count++ < subVertices.size()) {
//                                sql.append(",");
//                            }
//                        }
//                        sql.append(")");
//                        if (sqlgGraph.getSqlDialect().needsSemicolon()) {
//                            sql.append(";");
//                        }
//                        if (logger.isDebugEnabled()) {
//                            logger.debug(sql.toString());
//                        }
//                        conn = sqlgGraph.tx().getConnection();
//                        try (PreparedStatement preparedStatement = conn.prepareStatement(sql.toString())) {
//                            count = 1;
//                            for (SqlgVertex vertex : subVertices) {
//                                preparedStatement.setLong(count++, (Long) vertex.id());
//                            }
//                            preparedStatement.executeUpdate();
//                        } catch (SQLException e) {
//                            throw new RuntimeException(e);
//                        }
                    }

                }

//                createForeignKeys(sqlgGraph, schemaTable);

            }
        }
    }


    private void dropForeignKeys(SqlgGraph sqlgGraph, SchemaTable schemaTable) {

        SchemaManager schemaManager = sqlgGraph.getSchemaManager();
        Map<String, Set<String>> edgeForeignKeys = schemaManager.getEdgeForeignKeys();

        for (Map.Entry<String, Set<String>> edgeForeignKey : edgeForeignKeys.entrySet()) {
            String edgeTable = edgeForeignKey.getKey();
            Set<String> foreignKeys = edgeForeignKey.getValue();
            String[] schemaTableArray = edgeTable.split("\\.");

            for (String foreignKey : foreignKeys) {
                if (foreignKey.startsWith(schemaTable.toString() + "_")) {

                    Set<String> foreignKeyNames = getForeignKeyConstraintNames(sqlgGraph, schemaTableArray[0], schemaTableArray[1]);
                    for (String foreignKeyName : foreignKeyNames) {

                        StringBuilder sql = new StringBuilder();
                        sql.append("ALTER TABLE ");
                        sql.append(maybeWrapInQoutes(schemaTableArray[0]));
                        sql.append(".");
                        sql.append(maybeWrapInQoutes(schemaTableArray[1]));
                        sql.append(" DROP CONSTRAINT ");
                        sql.append(maybeWrapInQoutes(foreignKeyName));
                        if (needsSemicolon()) {
                            sql.append(";");
                        }
                        if (logger.isDebugEnabled()) {
                            logger.debug(sql.toString());
                        }
                        Connection conn = sqlgGraph.tx().getConnection();
                        try (PreparedStatement preparedStatement = conn.prepareStatement(sql.toString())) {
                            preparedStatement.executeUpdate();
                        } catch (SQLException e) {
                            throw new RuntimeException(e);
                        }

                    }
                }
            }
        }
    }

    private void createForeignKeys(SqlgGraph sqlgGraph, SchemaTable schemaTable) {
        SchemaManager schemaManager = sqlgGraph.getSchemaManager();
        Map<String, Set<String>> edgeForeignKeys = schemaManager.getEdgeForeignKeys();

        for (Map.Entry<String, Set<String>> edgeForeignKey : edgeForeignKeys.entrySet()) {
            String edgeTable = edgeForeignKey.getKey();
            Set<String> foreignKeys = edgeForeignKey.getValue();
            for (String foreignKey : foreignKeys) {
                if (foreignKey.startsWith(schemaTable.toString() + "_")) {
                    String[] schemaTableArray = edgeTable.split("\\.");
                    StringBuilder sql = new StringBuilder();
                    sql.append("ALTER TABLE ");
                    sql.append(maybeWrapInQoutes(schemaTableArray[0]));
                    sql.append(".");
                    sql.append(maybeWrapInQoutes(schemaTableArray[1]));
                    sql.append(" ADD FOREIGN KEY (");
                    sql.append(maybeWrapInQoutes(foreignKey));
                    sql.append(") REFERENCES ");
                    sql.append(maybeWrapInQoutes(schemaTable.getSchema()));
                    sql.append(".");
                    sql.append(maybeWrapInQoutes(SchemaManager.VERTEX_PREFIX + schemaTable.getTable()));
                    sql.append(" MATCH SIMPLE");
                    if (needsSemicolon()) {
                        sql.append(";");
                    }
                    if (logger.isDebugEnabled()) {
                        logger.debug(sql.toString());
                    }
                    Connection conn = sqlgGraph.tx().getConnection();
                    try (PreparedStatement preparedStatement = conn.prepareStatement(sql.toString())) {
                        preparedStatement.executeUpdate();
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
    }

    private void deleteEdges(SqlgGraph sqlgGraph, SchemaTable schemaTable, List<SqlgVertex> subVertices, Set<SchemaTable> labels, boolean inDirection) {
        for (SchemaTable inLabel : labels) {

            StringBuilder sql = new StringBuilder();
            sql.append("DELETE FROM ");
            sql.append(maybeWrapInQoutes(inLabel.getSchema()));
            sql.append(".");
            sql.append(maybeWrapInQoutes(inLabel.getTable()));
            sql.append(" WHERE ");
            sql.append(maybeWrapInQoutes(schemaTable.toString() + (inDirection ? SchemaManager.IN_VERTEX_COLUMN_END : SchemaManager.OUT_VERTEX_COLUMN_END)));
            sql.append(" IN (");
            int count = 1;
            for (Vertex vertexToDelete : subVertices) {
                sql.append("?");
                if (count++ < subVertices.size()) {
                    sql.append(",");
                }
            }
            sql.append(")");
            if (sqlgGraph.getSqlDialect().needsSemicolon()) {
                sql.append(";");
            }
            if (logger.isDebugEnabled()) {
                logger.debug(sql.toString());
            }
            Connection conn = sqlgGraph.tx().getConnection();
            try (PreparedStatement preparedStatement = conn.prepareStatement(sql.toString())) {
                count = 1;
                for (Vertex vertexToDelete : subVertices) {
                    preparedStatement.setLong(count++, ((RecordId) vertexToDelete.id()).getId());
                }
                int deleted = preparedStatement.executeUpdate();
                if (logger.isDebugEnabled()) {
                    logger.debug("Deleted " + deleted + " edges from " + inLabel.toString());
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void flushRemovedEdges(SqlgGraph sqlgGraph, Map<SchemaTable, List<SqlgEdge>> removeEdgeCache) {

        if (!removeEdgeCache.isEmpty()) {

            //split the list of edges, postgres has a 2 byte limit in the in clause
            for (Map.Entry<SchemaTable, List<SqlgEdge>> schemaEdges : removeEdgeCache.entrySet()) {

                List<SqlgEdge> edges = schemaEdges.getValue();
                int numberOfLoops = (edges.size() / PARAMETER_LIMIT);
                int previous = 0;
                for (int i = 1; i <= numberOfLoops + 1; i++) {

                    List<SqlgEdge> flattenedEdges = new ArrayList<>();
                    int subListTo = i * PARAMETER_LIMIT;
                    List<SqlgEdge> subEdges;
                    if (i <= numberOfLoops) {
                        subEdges = edges.subList(previous, subListTo);
                    } else {
                        subEdges = edges.subList(previous, edges.size());
                    }
                    previous = subListTo;

                    for (SchemaTable schemaTable : removeEdgeCache.keySet()) {
                        StringBuilder sql = new StringBuilder("DELETE FROM ");
                        sql.append(sqlgGraph.getSchemaManager().getSqlDialect().maybeWrapInQoutes(schemaTable.getSchema()));
                        sql.append(".");
                        sql.append(sqlgGraph.getSchemaManager().getSqlDialect().maybeWrapInQoutes((SchemaManager.EDGE_PREFIX) + schemaTable.getTable()));
                        sql.append(" WHERE ");
                        sql.append(sqlgGraph.getSchemaManager().getSqlDialect().maybeWrapInQoutes(SchemaManager.ID));
                        sql.append(" in (");
                        int count = 1;
                        for (SqlgEdge sqlgEdge : subEdges) {
                            flattenedEdges.add(sqlgEdge);
                            sql.append("?");
                            if (count++ < subEdges.size()) {
                                sql.append(",");
                            }
                        }
                        sql.append(")");
                        if (sqlgGraph.getSqlDialect().needsSemicolon()) {
                            sql.append(";");
                        }
                        if (logger.isDebugEnabled()) {
                            logger.debug(sql.toString());
                        }
                        Connection conn = sqlgGraph.tx().getConnection();
                        try (PreparedStatement preparedStatement = conn.prepareStatement(sql.toString())) {
                            count = 1;
                            for (SqlgEdge sqlgEdge : subEdges) {
                                preparedStatement.setLong(count++, ((RecordId) sqlgEdge.id()).getId());
                            }
                            preparedStatement.executeUpdate();
                        } catch (SQLException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            }
        }
    }

    @Override
    public String getBatchNull() {
        return BATCH_NULL;
    }

    private InputStream mapToEdge_InputStream(Pair<SortedSet<String>, Map<SqlgEdge, Triple<SqlgVertex, SqlgVertex, Map<String, Object>>>> edgeCache) {
        StringBuilder sb = new StringBuilder();
        int count = 1;
        for (Triple<SqlgVertex, SqlgVertex, Map<String, Object>> triple : edgeCache.getRight().values()) {
            sb.append(((RecordId) triple.getLeft().id()).getId());
            sb.append(COPY_COMMAND_DELIMITER);
            sb.append(((RecordId) triple.getMiddle().id()).getId());
            if (!edgeCache.getLeft().isEmpty()) {
                sb.append(COPY_COMMAND_DELIMITER);
            }
            int countKeys = 1;
            for (String key : edgeCache.getLeft()) {
                Object value = triple.getRight().get(key);
                if (value == null) {
                    sb.append(getBatchNull());
                } else if (value.getClass().isArray()) {
                    sb.append("{");
                    int length = java.lang.reflect.Array.getLength(value);
                    for (int i = 0; i < length; i++) {
                        String valueOfArray = java.lang.reflect.Array.get(value, i).toString();
                        sb.append(escapeSpecialCharacters(valueOfArray));
                        if (i < length - 1) {
                            sb.append(",");
                        }
                    }
                    sb.append("}");
                } else {
                    sb.append(escapeSpecialCharacters(value.toString()));
                }
                if (countKeys < edgeCache.getLeft().size()) {
                    sb.append(COPY_COMMAND_DELIMITER);
                }
                countKeys++;
            }
            if (count++ < edgeCache.getRight().size()) {
                sb.append("\n");
            }
        }
        return new ByteArrayInputStream(sb.toString().getBytes());
    }

    private InputStream mapToLabeledVertex_InputStream(Pair<SortedSet<String>, Map<SqlgVertex, Map<String, Object>>> vertexCache) {
        //String str = "2,peter\n3,john";
        StringBuilder sb = new StringBuilder();
        int count = 1;
        for (SqlgVertex sqlgVertex : vertexCache.getRight().keySet()) {
            Map<String, Object> triple = vertexCache.getRight().get(sqlgVertex);
            //set the internal batch id to be used with inserting batch edges
            if (!vertexCache.getLeft().isEmpty()) {
                int countKeys = 1;
                for (String key : vertexCache.getLeft()) {
                    if (countKeys > 1 && countKeys <= vertexCache.getLeft().size()) {
                        sb.append(COPY_COMMAND_DELIMITER);
                    }
                    countKeys++;
                    Object value = triple.get(key);
                    if (value == null) {
                        sb.append(getBatchNull());
                    } else if (value.getClass().isArray()) {
                        sb.append("{");
                        int length = java.lang.reflect.Array.getLength(value);
                        for (int i = 0; i < length; i++) {
                            String valueOfArray = java.lang.reflect.Array.get(value, i).toString();
                            sb.append(escapeSpecialCharacters(valueOfArray));
                            if (i < length - 1) {
                                sb.append(",");
                            }
                        }
                        sb.append("}");
                    } else {
                        sb.append(escapeSpecialCharacters(value.toString()));
                    }
                }
            } else {
                sb.append("0");
            }
            if (count++ < vertexCache.getRight().size()) {
                sb.append("\n");
            }
        }
        return new ByteArrayInputStream(sb.toString().getBytes());
    }

    //In particular, the following characters must be preceded by a backslash if they appear as part of a column value:
    //backslash itself, newline, carriage return, and the current delimiter character.
    private String escapeSpecialCharacters(String s) {
        s = s.replace("\\", "\\\\");
        s = s.replace("\n", "\\\\n");
        s = s.replace("\r", "\\\\r");
        s = s.replace("\t", "\\\\t");
        return s;
    }

    @Override
    public String[] propertyTypeToSqlDefinition(PropertyType propertyType) {
        switch (propertyType) {
            case BOOLEAN:
                return new String[]{"BOOLEAN"};
            case SHORT:
                return new String[]{"SMALLINT"};
            case INTEGER:
                return new String[]{"INTEGER"};
            case LONG:
                return new String[]{"BIGINT"};
            case FLOAT:
                return new String[]{"REAL"};
            case DOUBLE:
                return new String[]{"DOUBLE PRECISION"};
            case LOCALDATE:
                return new String[]{"DATE"};
            case LOCALDATETIME:
                return new String[]{"TIMESTAMP WITH TIME ZONE"};
//            case ZONEDDATETIME:
//                return new String[]{"TIMESTAMP WITH TIME ZONE", "TEXT"};
            case LOCALTIME:
                return new String[]{"TIME WITH TIME ZONE"};
//            case PERIOD:
//                return new String[]{"INTEGER", "INTEGER", "INTEGER"};
//            case DURATION:
//                return new String[]{"BIGINT", "INTEGER"};
            case STRING:
                return new String[]{"TEXT"};
            case JSON:
                return new String[]{"JSONB"};
            case POINT:
                return new String[]{"geometry(POINT)"};
            case POLYGON:
                return new String[]{"geometry(POLYGON)"};
            case GEOGRAPHY_POINT:
                return new String[]{"geography(POINT, 4326)"};
            case GEOGRAPHY_POLYGON:
                return new String[]{"geography(POLYGON, 4326)"};
            case BYTE_ARRAY:
                return new String[]{"BYTEA"};
            case BOOLEAN_ARRAY:
                return new String[]{"BOOLEAN[]"};
            case SHORT_ARRAY:
                return new String[]{"SMALLINT[]"};
            case INTEGER_ARRAY:
                return new String[]{"INTEGER[]"};
            case LONG_ARRAY:
                return new String[]{"BIGINT[]"};
            case FLOAT_ARRAY:
                return new String[]{"REAL[]"};
            case DOUBLE_ARRAY:
                return new String[]{"DOUBLE PRECISION[]"};
            case STRING_ARRAY:
                return new String[]{"TEXT[]"};
            default:
                throw new IllegalStateException("Unknown propertyType " + propertyType.name());
        }
    }

    //TODO
    @Override
    public PropertyType sqlTypeToPropertyType(int sqlType, String typeName) {
        switch (sqlType) {
            case Types.BIT:
                return PropertyType.BOOLEAN;
            case Types.SMALLINT:
                return PropertyType.SHORT;
            case Types.INTEGER:
                return PropertyType.INTEGER;
            case Types.BIGINT:
                return PropertyType.LONG;
            case Types.REAL:
                return PropertyType.FLOAT;
            case Types.DOUBLE:
                return PropertyType.DOUBLE;
            case Types.VARCHAR:
                return PropertyType.STRING;
            case Types.TIMESTAMP:
                return PropertyType.LOCALDATETIME;
            case Types.DATE:
                return PropertyType.LOCALDATE;
            case Types.TIME:
                return PropertyType.LOCALTIME;
            case Types.OTHER:
                //this is a f up as only JSON can be used for other.
                //means all the gis data types which are also OTHER are not supported
                return PropertyType.JSON;
            case Types.BINARY:
                return PropertyType.BYTE_ARRAY;
            case Types.ARRAY:
                switch (typeName) {
                    case "_bool":
                        return PropertyType.BOOLEAN_ARRAY;
                    case "_int2":
                        return PropertyType.SHORT_ARRAY;
                    case "_int4":
                        return PropertyType.INTEGER_ARRAY;
                    case "_int8":
                        return PropertyType.LONG_ARRAY;
                    case "_float4":
                        return PropertyType.FLOAT_ARRAY;
                    case "_float8":
                        return PropertyType.DOUBLE_ARRAY;
                    case "_text":
                        return PropertyType.STRING_ARRAY;
                    default:
                        throw new RuntimeException("Array type not supported " + typeName);
                }
            default:
                throw new IllegalStateException("Unknown sqlType " + sqlType);
        }
    }

    @Override
    public int propertyTypeToJavaSqlType(PropertyType propertyType) {
        switch (propertyType) {
            case BOOLEAN:
                return Types.BOOLEAN;
            case SHORT:
                return Types.SMALLINT;
            case INTEGER:
                return Types.INTEGER;
            case LONG:
                return Types.BIGINT;
            case FLOAT:
                return Types.REAL;
            case DOUBLE:
                return Types.DOUBLE;
            case STRING:
                return Types.CLOB;
            case BYTE_ARRAY:
                return Types.ARRAY;
            case LOCALDATETIME:
                return Types.TIMESTAMP;
            case LOCALDATE:
                return Types.DATE;
            case LOCALTIME:
                return Types.TIME;
            case JSON:
                //TODO support other others like Geometry...
                return Types.OTHER;
            case BOOLEAN_ARRAY:
                return Types.ARRAY;
            case SHORT_ARRAY:
                return Types.ARRAY;
            case INTEGER_ARRAY:
                return Types.ARRAY;
            case LONG_ARRAY:
                return Types.ARRAY;
            case FLOAT_ARRAY:
                return Types.ARRAY;
            case DOUBLE_ARRAY:
                return Types.ARRAY;
            case STRING_ARRAY:
                return Types.ARRAY;
            default:
                throw new IllegalStateException("Unknown propertyType " + propertyType.name());
        }
    }

    @Override
    public void validateProperty(Object key, Object value) {
        if (key instanceof String && ((String) key).length() > 63) {
            validateColumnName((String) key);
        }
        if (value instanceof String) {
            return;
        }
        if (value instanceof Character) {
            return;
        }
        if (value instanceof Boolean) {
            return;
        }
        if (value instanceof Byte) {
            return;
        }
        if (value instanceof Short) {
            return;
        }
        if (value instanceof Integer) {
            return;
        }
        if (value instanceof Long) {
            return;
        }
        if (value instanceof Float) {
            return;
        }
        if (value instanceof Double) {
            return;
        }
        if (value instanceof LocalDate) {
            return;
        }
        if (value instanceof LocalDateTime) {
            return;
        }
        //TODO, needs schema db with types as it classes with regular LOCALDATETIME
//        if (value instanceof ZonedDateTime) {
//            return;
//        }
        if (value instanceof LocalTime) {
            return;
        }
        //TODO, needs schema db with types as it classes with regular Integer
//        if (value instanceof Period) {
//            return;
//        }
        //TODO, needs schema db with types as it classes with regular Long
//        if (value instanceof Duration) {
//            return;
//        }
        if (value instanceof JsonNode) {
            return;
        }
        if (value instanceof Point) {
            return;
        }
        if (value instanceof Polygon) {
            return;
        }
        if (value instanceof byte[]) {
            return;
        }
        if (value instanceof boolean[]) {
            return;
        }
        if (value instanceof char[]) {
            return;
        }
        if (value instanceof short[]) {
            return;
        }
        if (value instanceof int[]) {
            return;
        }
        if (value instanceof long[]) {
            return;
        }
        if (value instanceof float[]) {
            return;
        }
        if (value instanceof double[]) {
            return;
        }
        if (value instanceof String[]) {
            return;
        }
        if (value instanceof Character[]) {
            return;
        }
        if (value instanceof Boolean[]) {
            return;
        }
        if (value instanceof Byte[]) {
            return;
        }
        if (value instanceof Short[]) {
            return;
        }
        if (value instanceof Integer[]) {
            return;
        }
        if (value instanceof Long[]) {
            return;
        }
        if (value instanceof Float[]) {
            return;
        }
        if (value instanceof Double[]) {
            return;
        }
        throw Property.Exceptions.dataTypeOfPropertyValueNotSupported(value);
    }

    @Override
    public boolean needForeignKeyIndex() {
        return true;
    }

    private Set<String> getForeignKeyConstraintNames(SqlgGraph sqlgGraph, String foreignKeySchema, String foreignKeyTable) {
        Set<String> result = new HashSet<>();
        Connection conn = sqlgGraph.tx().getConnection();
        DatabaseMetaData metadata;
        try {
            metadata = conn.getMetaData();
            String childCatalog = null;
            String childSchemaPattern = foreignKeySchema;
            String childTableNamePattern = foreignKeyTable;
            ResultSet resultSet = metadata.getImportedKeys(childCatalog, childSchemaPattern, childTableNamePattern);
            while (resultSet.next()) {
                result.add(resultSet.getString("FK_NAME"));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    public boolean supportsClientInfo() {
        return true;
    }

    public void validateSchemaName(String schema) {
        if (schema.length() > getMinimumSchemaNameLength()) {
            throw SqlgExceptions.invalidSchemaName("Postgresql schema names can only be 63 characters. " + schema + " exceeds that");
        }
    }

    public void validateTableName(String table) {
        if (table.length() > getMinimumTableNameLength()) {
            throw SqlgExceptions.invalidTableName("Postgresql table names can only be 63 characters. " + table + " exceeds that");
        }
    }

    @Override
    public void validateColumnName(String column) {
        super.validateColumnName(column);
        if (column.length() > getMinimumColumnNameLength()) {
            throw SqlgExceptions.invalidColumnName("Postgresql column names can only be 63 characters. " + column + " exceeds that");
        }
    }

    public int getMinimumSchemaNameLength() {
        return 63;
    }

    public int getMinimumTableNameLength() {
        return 63;
    }

    public int getMinimumColumnNameLength() {
        return 63;
    }

    @Override
    public boolean supportsILike() {
        return Boolean.TRUE;
    }

    @Override
    public boolean needsTimeZone() {
        return Boolean.TRUE;
    }

    @Override
    public List<String> getSpacialRefTable() {
        return Arrays.asList("spatial_ref_sys");
    }

    @Override
    public List<String> getGisSchemas() {
        return Arrays.asList("tiger", "tiger_data", "topology");
    }

    @Override
    public void setJson(PreparedStatement preparedStatement, int parameterStartIndex, JsonNode json) {
        PGobject jsonObject = new PGobject();
        jsonObject.setType("jsonb");
        try {
            jsonObject.setValue(json.toString());
            preparedStatement.setObject(parameterStartIndex, jsonObject);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setPoint(PreparedStatement preparedStatement, int parameterStartIndex, Object point) {
        Preconditions.checkArgument(point instanceof Point, "point must be an instance of " + Point.class.getName());
        try {
            preparedStatement.setObject(parameterStartIndex, new PGgeometry((Point) point));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setPolygon(PreparedStatement preparedStatement, int parameterStartIndex, Object polygon) {
        Preconditions.checkArgument(polygon instanceof Polygon, "polygon must be an instance of " + Polygon.class.getName());
        try {
            preparedStatement.setObject(parameterStartIndex, new PGgeometry((Polygon) polygon));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setGeographyPoint(PreparedStatement preparedStatement, int parameterStartIndex, Object point) {
        Preconditions.checkArgument(point instanceof GeographyPoint, "point must be an instance of " + GeographyPoint.class.getName());
        try {
            preparedStatement.setObject(parameterStartIndex, new PGgeometry((GeographyPoint) point));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void handleOther(Map<String, Object> properties, String columnName, Object o) {
        if (o instanceof PGgeometry) {
            properties.put(columnName, ((PGgeometry) o).getGeometry());
        } else if (((PGobject) o).getType().equals("geography")) {
            try {
                Geometry geometry = PGgeometry.geomFromString(((PGobject) o).getValue());
                if (geometry instanceof Point) {
                    properties.put(columnName, new GeographyPoint((Point) geometry));
                } else if (geometry instanceof Polygon) {
                    properties.put(columnName, new GeographyPolygon((Polygon) geometry));
                } else {
                    throw new IllegalStateException("Gis type " + geometry.getClass().getName() + " is not supported.");
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        } else {
            //Assume json for now
            ObjectMapper objectMapper = new ObjectMapper();
            try {
                JsonNode jsonNode = objectMapper.readTree(((PGobject) o).getValue());
                properties.put(columnName, jsonNode);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public boolean supportsJson() {
        return true;
    }

    @Override
    public OutputStream streamSql(SqlgGraph sqlgGraph, String sql) {
        C3P0ProxyConnection conn = (C3P0ProxyConnection) sqlgGraph.tx().getConnection();
        PGConnection pgConnection;
        try {
            pgConnection = conn.unwrap(PGConnection.class);
            return new PGCopyOutputStream(pgConnection, sql);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public InputStream inputStreamSql(SqlgGraph sqlgGraph, String sql) {
        C3P0ProxyConnection conn = (C3P0ProxyConnection) sqlgGraph.tx().getConnection();
        PGConnection pgConnection;
        try {
            pgConnection = conn.unwrap(PGConnection.class);
            return new PGCopyInputStream(pgConnection, sql);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void copyInBulkTempEdges(SqlgGraph sqlgGraph, SchemaTable schemaTable, List<? extends Pair<String, String>> uids) {
        try {
            StringBuffer sql = new StringBuffer();
            sql.append("COPY ");
            sql.append(maybeWrapInQoutes(schemaTable.getTable()));
            sql.append(" (");
            int count = 1;
            for (String key : Arrays.asList("in", "out")) {
                if (count > 1 && count <= 2) {
                    sql.append(", ");
                }
                count++;
                sql.append(maybeWrapInQoutes(key));
            }
            sql.append(")");
            sql.append(" FROM stdin DELIMITER '");
            sql.append(COPY_COMMAND_DELIMITER);
            sql.append("';");
            if (logger.isDebugEnabled()) {
                logger.debug(sql.toString());
            }
            OutputStream out = streamSql(sqlgGraph, sql.toString());
            for (Pair<String, String> uid : uids) {
                out.write(uid.getLeft().getBytes());
                out.write(COPY_COMMAND_DELIMITER.getBytes());
                out.write(uid.getRight().getBytes());
                out.write("\n".getBytes());
            }
            out.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void bulkAddEdges(SqlgGraph sqlgGraph, SchemaTable in, SchemaTable out, String edgeLabel, Pair<String, String> idFields, List<? extends Pair<String, String>> uids) {
        if (!sqlgGraph.tx().isInStreamingBatchMode() && !sqlgGraph.tx().isInStreamingWithLockBatchMode()) {
            throw SqlgExceptions.invalidMode("Transaction must be in " + BatchManager.BatchModeType.STREAMING + " or " + BatchManager.BatchModeType.STREAMING_WITH_LOCK + " mode for bulkAddEdges");
        }
        //create temp table and copy the uids into it
        Map<String, PropertyType> columns = new HashMap<>();
        columns.put("out", PropertyType.STRING);
        columns.put("in", PropertyType.STRING);
        SecureRandom random = new SecureRandom();
        byte bytes[] = new byte[6];
        random.nextBytes(bytes);
        String tmpTableIdentified = Base64.getEncoder().encodeToString(bytes);
        tmpTableIdentified = SchemaManager.BULK_TEMP_EDGE + tmpTableIdentified;
        sqlgGraph.getSchemaManager().createTempTable(tmpTableIdentified, columns);
        this.copyInBulkTempEdges(sqlgGraph, SchemaTable.of(in.getSchema(), tmpTableIdentified), uids);
        //execute copy from select. select the edge ids to copy into the new table by joining on the temp table
        sqlgGraph.getSchemaManager().ensureEdgeTableExist(in.getSchema(), edgeLabel, out, in);

        StringBuilder sql = new StringBuilder("INSERT INTO \n");
        sql.append(this.maybeWrapInQoutes(in.getSchema()));
        sql.append(".");
        sql.append(this.maybeWrapInQoutes(SchemaManager.EDGE_PREFIX + edgeLabel));
        sql.append(" (");
        sql.append(this.maybeWrapInQoutes(in.getSchema() + "." + in.getTable() + SchemaManager.OUT_VERTEX_COLUMN_END));
        sql.append(",");
        sql.append(this.maybeWrapInQoutes(out.getSchema() + "." + out.getTable() + SchemaManager.IN_VERTEX_COLUMN_END));
        sql.append(") \n");
        sql.append("select _in.\"" + SchemaManager.ID + "\" as \"");
        sql.append(in.getSchema() + "." + in.getTable() + SchemaManager.OUT_VERTEX_COLUMN_END);
        sql.append("\", _out.\"" + SchemaManager.ID + "\" as \"");
        sql.append(out.getSchema() + "." + out.getTable() + SchemaManager.IN_VERTEX_COLUMN_END);
        sql.append("\" FROM ");
        sql.append(this.maybeWrapInQoutes(in.getSchema()));
        sql.append(".");
        sql.append(this.maybeWrapInQoutes(SchemaManager.VERTEX_PREFIX + in.getTable()));
        sql.append(" _in join ");
        sql.append(this.maybeWrapInQoutes(tmpTableIdentified) + " ab on ab.in = _in." + this.maybeWrapInQoutes(idFields.getLeft()) + " join ");
        sql.append(this.maybeWrapInQoutes(out.getSchema()));
        sql.append(".");
        sql.append(this.maybeWrapInQoutes(SchemaManager.VERTEX_PREFIX + out.getTable()));
        sql.append(" _out on ab.out = _out." + this.maybeWrapInQoutes(idFields.getRight()));
        if (logger.isDebugEnabled()) {
            logger.debug(sql.toString());
        }
        Connection conn = sqlgGraph.tx().getConnection();
        try (PreparedStatement preparedStatement = conn.prepareStatement(sql.toString())) {
            preparedStatement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void lockTable(SqlgGraph sqlgGraph, SchemaTable schemaTable, String prefix) {
        Preconditions.checkArgument(prefix.equals(SchemaManager.VERTEX_PREFIX) || prefix.equals(SchemaManager.EDGE_PREFIX), "prefix must be " + SchemaManager.VERTEX_PREFIX + " or " + SchemaManager.EDGE_PREFIX);
        StringBuilder sql = new StringBuilder();
        sql.append("LOCK TABLE ");
        sql.append(sqlgGraph.getSchemaManager().getSqlDialect().maybeWrapInQoutes(schemaTable.getSchema()));
        sql.append(".");
        sql.append(sqlgGraph.getSchemaManager().getSqlDialect().maybeWrapInQoutes(prefix + schemaTable.getTable()));
        sql.append(" IN SHARE MODE");
        if (this.needsSemicolon()) {
            sql.append(";");
        }
        if (logger.isDebugEnabled()) {
            logger.debug(sql.toString());
        }
        Connection conn = sqlgGraph.tx().getConnection();
        try (PreparedStatement preparedStatement = conn.prepareStatement(sql.toString())) {
            preparedStatement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void alterSequenceCacheSize(SqlgGraph sqlgGraph, SchemaTable schemaTable, String sequence, int batchSize) {
        StringBuilder sql = new StringBuilder();
        sql.append("ALTER SEQUENCE ");
        sql.append(sequence);
        sql.append(" CACHE ");
        sql.append(String.valueOf(batchSize));
        if (this.needsSemicolon()) {
            sql.append(";");
        }
        if (logger.isDebugEnabled()) {
            logger.debug(sql.toString());
        }
        Connection conn = sqlgGraph.tx().getConnection();
        try (PreparedStatement preparedStatement = conn.prepareStatement(sql.toString())) {
            preparedStatement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public long nextSequenceVal(SqlgGraph sqlgGraph, SchemaTable schemaTable, String prefix) {
        Preconditions.checkArgument(prefix.equals(SchemaManager.VERTEX_PREFIX) || prefix.equals(SchemaManager.EDGE_PREFIX), "prefix must be " + SchemaManager.VERTEX_PREFIX + " or " + SchemaManager.EDGE_PREFIX);
        long result;
        Connection conn = sqlgGraph.tx().getConnection();
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT NEXTVAL('\"" + schemaTable.getSchema() + "\".\"" + prefix + schemaTable.getTable() + "_ID_seq\"');");
        if (logger.isDebugEnabled()) {
            logger.debug(sql.toString());
        }
        try (PreparedStatement preparedStatement = conn.prepareStatement(sql.toString())) {
            ResultSet resultSet = preparedStatement.executeQuery();
            resultSet.next();
            result = resultSet.getLong(1);
            resultSet.close();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    @Override
    public long currSequenceVal(SqlgGraph sqlgGraph, SchemaTable schemaTable, String prefix) {
        Preconditions.checkArgument(prefix.equals(SchemaManager.VERTEX_PREFIX) || prefix.equals(SchemaManager.EDGE_PREFIX), "prefix must be " + SchemaManager.VERTEX_PREFIX + " or " + SchemaManager.EDGE_PREFIX);
        long result;
        Connection conn = sqlgGraph.tx().getConnection();
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT CURRVAL('\"" + schemaTable.getSchema() + "\".\"" + prefix + schemaTable.getTable() + "_ID_seq\"');");
        if (logger.isDebugEnabled()) {
            logger.debug(sql.toString());
        }
        try (PreparedStatement preparedStatement = conn.prepareStatement(sql.toString())) {
            ResultSet resultSet = preparedStatement.executeQuery();
            resultSet.next();
            result = resultSet.getLong(1);
            resultSet.close();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    @Override
    public String sequenceName(SqlgGraph sqlgGraph, SchemaTable outSchemaTable, String prefix) {
        Preconditions.checkArgument(prefix.equals(SchemaManager.VERTEX_PREFIX) || prefix.equals(SchemaManager.EDGE_PREFIX), "prefix must be " + SchemaManager.VERTEX_PREFIX + " or " + SchemaManager.EDGE_PREFIX);
//        select pg_get_serial_sequence('public."V_Person"', 'ID')
        String result;
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT pg_get_serial_sequence('\"");
        sql.append(outSchemaTable.getSchema());
        sql.append("\".\"");
        sql.append(prefix).append(outSchemaTable.getTable()).append("\"', '" + SchemaManager.ID + "')");
        if (logger.isDebugEnabled()) {
            logger.debug(sql.toString());
        }
        Connection conn = sqlgGraph.tx().getConnection();
        try (PreparedStatement preparedStatement = conn.prepareStatement(sql.toString())) {
            ResultSet resultSet = preparedStatement.executeQuery();
            resultSet.next();
            result = resultSet.getString(1);
            resultSet.close();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    @Override
    public boolean supportsBulkWithinOut() {
        return true;
    }

    @Override
    public boolean isPostgresql() {
        return true;
    }

    @Override
    public void registerGisDataTypes(Connection connection) {
        try {
            ((Jdbc4Connection) ((com.mchange.v2.c3p0.impl.NewProxyConnection) connection).unwrap(Jdbc4Connection.class)).addDataType("geometry", "org.postgis.PGgeometry");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public <T> T getGis(SqlgGraph sqlgGraph) {
        Gis gis = Gis.GIS;
        gis.setSqlgGraph(sqlgGraph);
        return (T) gis;
    }

    @Override
    public String afterCreateTemporaryTableStatement() {
        return "ON COMMIT DROP";
    }
}
