package com.criteo.drelephant.heuristics;

import com.avaje.ebean.Ebean;
import com.avaje.ebean.SqlRow;
import com.avaje.ebean.Transaction;
import com.linkedin.drelephant.analysis.Severity;
import com.linkedin.drelephant.util.Utils;
import models.AppHeuristicResult;
import models.AppHeuristicResultDetails;
import models.AppResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class GarmadonTransferHeuristic {
    private static final Logger LOGGER = LoggerFactory.getLogger(GarmadonTransferHeuristic.class);

    private static final String TABLENAME_PREFIX = "garmadon_";
    private static final String HEURISTIC_RESULT_TABLENAME = TABLENAME_PREFIX + "yarn_app_heuristic_result";
    private static final String HEURISTIC_RESULT_DETAILS_TABLENAME = TABLENAME_PREFIX + "yarn_app_heuristic_result_details";

    private static final String SELECT_HEURISTIC_RESULT_SQL = "SELECT * FROM " + HEURISTIC_RESULT_TABLENAME
            + " WHERE yarn_app_result_id = :yarn_app_result_id";
    private static final String DELETE_HEURISTIC_RESULT_SQL = "DELETE FROM " + HEURISTIC_RESULT_TABLENAME
            + " WHERE yarn_app_result_id = :yarn_app_result_id";
    private static final String SELECT_HEURISTIC_RESULT_DETAILS_SQL = "SELECT * FROM " + HEURISTIC_RESULT_DETAILS_TABLENAME
            + " WHERE yarn_app_heuristic_result_id = :yarn_app_heuristic_result_id";
    private static final String DELETE_HEURISTIC_RESULT_DETAILS_SQL = "DELETE FROM " + HEURISTIC_RESULT_DETAILS_TABLENAME
            + " WHERE yarn_app_heuristic_result_id = :yarn_app_heuristic_result_id";

    public GarmadonTransferHeuristic() {
    }

    public void transfert() {
        // Select all app in garmadon table
        LOGGER.debug("Select all distinct yarn_app_result_id from " + HEURISTIC_RESULT_TABLENAME);
        String findApps =
                "SELECT DISTINCT yarn_app_result_id FROM " + HEURISTIC_RESULT_TABLENAME + ";";
        List<SqlRow> rows = Ebean.createSqlQuery(findApps).findList();

        for (SqlRow row : rows) {
            String yarn_app_result_id = row.getString("yarn_app_result_id");
            AppResult appResult = Ebean.find(AppResult.class, yarn_app_result_id);
            if (appResult != null) {
                LOGGER.debug("Insert data for " + yarn_app_result_id);
                insertData(appResult, yarn_app_result_id);
            }
        }
    }

    private void insertData(AppResult appResult, String yarn_app_result_id) {
        // Set severity
        Severity worstSeverity = appResult.severity;

        Transaction transaction = null;
        try {
            transaction = Ebean.beginTransaction();

            List<SqlRow> rows = Ebean.createSqlQuery(SELECT_HEURISTIC_RESULT_SQL)
                    .setParameter("yarn_app_result_id", yarn_app_result_id)
                    .findList();
            for (SqlRow row : rows) {
                AppHeuristicResult appHeuristicResult = new AppHeuristicResult();
                String id = row.getString("id");
                appHeuristicResult.heuristicClass = Utils.truncateField(row.getString("heuristic_class"),
                        AppHeuristicResult.HEURISTIC_CLASS_LIMIT, id);
                appHeuristicResult.heuristicName = Utils.truncateField(row.getString("heuristic_name"),
                        AppHeuristicResult.HEURISTIC_NAME_LIMIT, id);
                appHeuristicResult.score = row.getInteger("score");
                appHeuristicResult.severity = Severity.byValue(row.getInteger("severity"));

                List<SqlRow> detail_rows = Ebean.createSqlQuery(SELECT_HEURISTIC_RESULT_DETAILS_SQL)
                        .setParameter("yarn_app_heuristic_result_id", id)
                        .findList();

                for (SqlRow detail_row : detail_rows) {
                    AppHeuristicResultDetails heuristicDetail = new AppHeuristicResultDetails();
                    heuristicDetail.name = Utils.truncateField(detail_row.getString("name"),
                            AppHeuristicResultDetails.NAME_LIMIT, id);
                    heuristicDetail.value = Utils.truncateField(detail_row.getString("value"),
                            AppHeuristicResultDetails.VALUE_LIMIT, id);
                    heuristicDetail.details = Utils.truncateField(detail_row.getString("details"),
                            AppHeuristicResultDetails.DETAILS_LIMIT, id);
                    heuristicDetail.yarnAppHeuristicResult = appHeuristicResult;
                    appHeuristicResult.yarnAppHeuristicResultDetails.add(heuristicDetail);
                }

                appResult.yarnAppHeuristicResults.add(appHeuristicResult);
                worstSeverity = Severity.max(worstSeverity, appHeuristicResult.severity);
                appResult.severity = worstSeverity;

                LOGGER.debug("Delete yarn_app_heuristic_result_id " + id + " on " + HEURISTIC_RESULT_DETAILS_TABLENAME);
                Ebean.createSqlUpdate(DELETE_HEURISTIC_RESULT_DETAILS_SQL)
                        .setParameter("yarn_app_heuristic_result_id", id)
                        .execute();
            }

            LOGGER.debug("Delete yarn_app_result_id " + yarn_app_result_id + " on " + HEURISTIC_RESULT_TABLENAME);
            Ebean.createSqlUpdate(DELETE_HEURISTIC_RESULT_SQL)
                    .setParameter("yarn_app_result_id", yarn_app_result_id)
                    .execute();

            LOGGER.debug("Save appResult" + appResult.id);
            appResult.save();
            transaction.commit();
        } catch (Exception e) {
            LOGGER.error("Unexpected error occurred while inserting garmadon heuristics", e);
            throw new RuntimeException(e);
        } finally {
            if (transaction != null) {
                transaction.end();
            }
        }
    }

}
