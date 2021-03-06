package com.anjlab.csv2db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;

import javax.script.ScriptEngine;
import javax.script.ScriptException;

public class MergeRecordHandler extends AbstractInsertUpdateRecordHandler
{
    protected AbstractRecordHandler insertRecordHandler;

    protected PreparedStatement updateStatement;

    private int numberOfStatementsInBatch;

    public MergeRecordHandler(Configuration config, Connection connection, ScriptEngine scriptEngine) throws SQLException, ScriptException
    {
        super(config, scriptEngine, connection);

        StringBuilder setClause = new StringBuilder();

        for (String targetTableColumnName : getColumnNamesWithUpdateValues())
        {
            if (setClause.length() > 0)
            {
                setClause.append(", ");
            }
            setClause.append(targetTableColumnName).append(" = ");

            ValueDefinition definition = config.getUpdateValues().get(targetTableColumnName);

            if (definition.producesSQL())
            {
                setClause.append(definition.eval(targetTableColumnName, null, scriptEngine));
            }
            else
            {
                setClause.append("?");
            }
        }

        for (String targetTableColumnName : getOrderedTableColumnNames())
        {
            if (setClause.length() > 0)
            {
                setClause.append(", ");
            }
            setClause.append(targetTableColumnName).append(" = ?");
        }

        StringBuilder updateClause =
                new StringBuilder("UPDATE ")
                        .append(config.getTargetTable())
                        .append(" SET ")
                        .append(setClause)
                        .append(" WHERE ")
                        .append(buildWhereClause());

        this.updateStatement = connection.prepareStatement(updateClause.toString());

        this.insertRecordHandler = new InsertRecordHandler(config, connection, scriptEngine);
    }

    @Override
    protected void performInsert(Map<String, Object> nameValues)
            throws SQLException, ConfigurationException, ScriptException
    {
        insertRecordHandler.handleRecord(nameValues);
    }

    @Override
    protected void performUpdate(Map<String, Object> nameValues)
            throws ScriptException, SQLException, ConfigurationException
    {
        int parameterIndex = 1;

        //  Set parameters for the SET clause
        for (String targetTableColumnName : getColumnNamesWithUpdateValues())
        {
            ValueDefinition definition = config.getUpdateValues().get(targetTableColumnName);

            if (!definition.producesSQL())
            {
                Object columnValue = definition.eval(targetTableColumnName, nameValues, scriptEngine);

                updateStatement.setObject(parameterIndex++, columnValue);
            }
        }

        for (String targetTableColumnName : getOrderedTableColumnNames())
        {
            updateStatement.setObject(parameterIndex++, transform(targetTableColumnName, nameValues));
        }

        //  Set parameters for the WHERE clause
        for (String primaryKeyColumnName : config.getPrimaryKeys())
        {
            Object primaryKeyColumnValue = nameValues.get(primaryKeyColumnName);

            updateStatement.setObject(parameterIndex++, primaryKeyColumnValue);
        }

        numberOfStatementsInBatch++;

        updateStatement.addBatch();

        checkBatchExecution(config.getBatchSize());
    }

    private void checkBatchExecution(int limit) throws SQLException
    {
        if (numberOfStatementsInBatch >= limit)
        {
            updateStatement.executeBatch();

            updateStatement.clearParameters();

            numberOfStatementsInBatch = 0;
        }
    }

    @Override
    public void close()
    {
        try
        {
            super.executeBatch();

            checkBatchExecution(0);

            insertRecordHandler.close();
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
        finally
        {
            closeQuietly(updateStatement);
            super.close();
        }
    }
}