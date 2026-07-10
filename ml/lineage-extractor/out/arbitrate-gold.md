# 059 gold C pro 仲裁复标报告

- 争议空脚本（gold 空 + 模型抽出表）：43
- pro 判定假空翻成非空（label noise 确认）：**12**
- pro 也判空（模型 FP 属实）：31
- token：入 0 出 0 → **约 ¥0.00**

## 翻标样本（原空→pro 抽出真血缘）

- #7 → ['mimi_ws_1.desynpuf.beneficiary_summary', 'mimi_ws_1.desynpuf.carrier_claims', 'mimi_ws_1.desynpuf.inpatient_claims', 'mimi_ws_1.desynpuf.outpatient_claims', 'mimi_ws_1.desynpuf.prescription_drug_events']  |  # Databricks notebook source # MAGIC %run /Workspace/Repos/yubin.park@mimilabs.ai/mimi-com
- #11 → ['demo_ib.dev.orders_enriched', 'demo_ib.dev.user_daily_stats', 'demo_ib.dev.category_daily_stats']  |  import os from pyspark.sql import SparkSession  if __name__ == "__main__":     # ---------
- #20 → ['person.addresstype']  |  import os from delta import * from pyspark.sql import SparkSession  def main():      schem
- #32 → ['csv_skeleton']  |  # Databricks notebook source from pyspark.sql import functions as F  # COMMAND ---------- 
- #33 → ['vw_rds_oac_dim_whs', 'pcm_ppp_ref_dim_whs']  |  # Databricks notebook source # MAGIC %md # MAGIC #### Input  # COMMAND ----------  # MAGIC
- #42 → ['asd_output', 'asd_output_raw', 'asd_csv_master', 'asd_metric_name_lookup', 'asd_output_suppressed', 'asd_output', 'asd_output_raw', 'asd_output_suppressed', 'asd_main_output_raw', 'asd_main_output', 'asd_dq_output_raw', 'asd_dq_output']  |  # Databricks notebook source # MAGIC %md # MAGIC #Autism Statistics # MAGIC ##Rolling 12 m
- #48 → ['Yahoo-Finance-Ticker-Symbols.csv']  |  # -*- coding: utf-8 -*- """ Created on Fri Sep 27 14:36:49 2019  @author: Kaushik """
- #50 → ['orders', 'supplier_stats']  |  from pyspark.sql import SparkSession from pyspark.sql.functions import col, to_date, hour,
- #55 → ['$db_output.validcodes']  |  # Databricks notebook source # MAGIC %md # MAGIC  # MAGIC # NB this contains all ValidCode
- #75 → ['nasa_log.log']  |  from pyspark.sql import SparkSession from pyspark.sql.functions import * from pyspark.sql.
- #94 → ['users.wesley_pasfield.football_pbp']  |  # Databricks notebook source # MAGIC %pip install polars nflreadpy # MAGIC dbutils.library
- #141 → ['default.fsimage_tbl']  |  from pyspark.sql.types import StringType from pyspark.sql.types import ArrayType from pysp
