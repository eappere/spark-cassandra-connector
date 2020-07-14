/*
 * Copyright DataStax, Inc.
 *
 * Please see the included license file for details.
 */

package org.apache.spark.sql.cassandra

import com.datastax.oss.driver.api.core.metadata.schema.{ColumnMetadata, TableMetadata}
import com.datastax.spark.connector.cql.{ColumnDef, TableDef}
import com.datastax.spark.connector.util.Logging
import org.apache.spark.SparkConf
import org.apache.spark.sql.cassandra.CassandraSourceRelation.InClauseToFullTableScanConversionThreshold
import org.apache.spark.sql.cassandra.PredicateOps.FilterOps.{columnName, isInPredicate}
import org.apache.spark.sql.sources.{Filter, In}

object InClausePredicateRules extends CassandraPredicateRules with Logging {

  def inCrossProductSize(filters: Set[Filter]): Long =
    filters.toSeq.collect { case in@In(_, _) => in.values.length }.product

  private def pushFiltersToSpark(predicates: AnalyzedPredicates, filters: Set[Filter]): AnalyzedPredicates =
    predicates.copy(
      handledBySpark = predicates.handledBySpark ++ filters,
      handledByCassandra = predicates.handledByCassandra -- filters)

  private def columnsFilters(filters: Set[Filter], columns: Seq[ColumnMetadata]): Set[Filter] =
    filters.filter(f => columns.exists(ColumnDef.columnName(_) == columnName(f)))

  private def filterOutHugeInClausePredicates(
    predicates: AnalyzedPredicates,
    table: TableMetadata,
    sparkConf: SparkConf): AnalyzedPredicates = {
    val fullTableScanConversionThreshold = sparkConf.getLong(
      InClauseToFullTableScanConversionThreshold.name,
      InClauseToFullTableScanConversionThreshold.default)

    val inFilters = predicates.handledByCassandra.filter(isInPredicate)

    val partitionColumnsFilters = columnsFilters(inFilters, TableDef.partitionKey(table))
    val clusteringColumnsFilters = columnsFilters(inFilters, TableDef.clusteringColumns(table))

    val partitionCartesianSize = inCrossProductSize(partitionColumnsFilters)
    val clusteringCartesianSize = inCrossProductSize(clusteringColumnsFilters)

    if (partitionCartesianSize * clusteringCartesianSize < fullTableScanConversionThreshold) {
      predicates
    } else if (partitionCartesianSize < fullTableScanConversionThreshold) {
      logInfo(s"Number of key combinations in 'IN' clauses exceeds ${InClauseToFullTableScanConversionThreshold.name} " +
        s"($fullTableScanConversionThreshold), clustering columns filters are not pushed down.")
      pushFiltersToSpark(predicates, clusteringColumnsFilters)
    } else {
      logInfo(s"Number of key combinations in 'IN' clauses exceeds ${InClauseToFullTableScanConversionThreshold.name} " +
        s"($fullTableScanConversionThreshold), partition key filters are not pushed down. This results in full table " +
        s"scan with Spark side filtering.")
      pushFiltersToSpark(predicates, partitionColumnsFilters ++ clusteringColumnsFilters)
    }
  }

  override def apply(predicates: AnalyzedPredicates, table: TableMetadata, conf: SparkConf): AnalyzedPredicates = {
    filterOutHugeInClausePredicates(predicates, table, conf)
  }
}
