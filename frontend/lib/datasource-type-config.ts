/**
 * Frontend configuration for each datasource type's connection form.
 * Controls which fields are shown, their labels, defaults, and JDBC URL template.
 */

export interface FieldConfig {
  key: string
  labelKey: string  // i18n key under "datasources.form."
  required?: boolean
  defaultValue?: string | number
  type?: "text" | "number" | "password"
  placeholder?: string
}

export interface DatasourceTypeConfig {
  category: string
  fields: FieldConfig[]
  advancedFields?: FieldConfig[]
  jdbcUrlTemplate?: string
  noJdbcUrl?: boolean
}

export const DATASOURCE_TYPE_CONFIG: Record<string, DatasourceTypeConfig> = {
  // ─── RDB ───────────────────────────────────────
  MYSQL: {
    category: "RDB",
    fields: [
      { key: "host", labelKey: "host", required: true, placeholder: "10.0.0.20" },
      { key: "port", labelKey: "port", required: true, defaultValue: 3306, type: "number" },
      { key: "databaseName", labelKey: "database", required: true, placeholder: "mydb" },
      { key: "username", labelKey: "username", required: true },
      { key: "password", labelKey: "password", required: true, type: "password" },
    ],
    jdbcUrlTemplate: "jdbc:mysql://{host}:{port}/{databaseName}",
  },
  POSTGRES: {
    category: "RDB",
    fields: [
      { key: "host", labelKey: "host", required: true, placeholder: "10.0.0.20" },
      { key: "port", labelKey: "port", required: true, defaultValue: 5432, type: "number" },
      { key: "databaseName", labelKey: "database", required: true, placeholder: "mydb" },
      { key: "username", labelKey: "username", required: true },
      { key: "password", labelKey: "password", required: true, type: "password" },
    ],
    jdbcUrlTemplate: "jdbc:postgresql://{host}:{port}/{databaseName}",
  },
  ORACLE: {
    category: "RDB",
    fields: [
      { key: "host", labelKey: "host", required: true, placeholder: "10.0.0.20" },
      { key: "port", labelKey: "port", required: true, defaultValue: 1521, type: "number" },
      { key: "databaseName", labelKey: "sid", required: true, placeholder: "ORCL" },
      { key: "username", labelKey: "username", required: true },
      { key: "password", labelKey: "password", required: true, type: "password" },
    ],
    jdbcUrlTemplate: "jdbc:oracle:thin:@{host}:{port}:{databaseName}",
  },
  SQLSERVER: {
    category: "RDB",
    fields: [
      { key: "host", labelKey: "host", required: true, placeholder: "10.0.0.20" },
      { key: "port", labelKey: "port", required: true, defaultValue: 1433, type: "number" },
      { key: "databaseName", labelKey: "database", required: true, placeholder: "mydb" },
      { key: "username", labelKey: "username", required: true },
      { key: "password", labelKey: "password", required: true, type: "password" },
    ],
    jdbcUrlTemplate: "jdbc:sqlserver://{host}:{port};databaseName={databaseName}",
  },
  MARIADB: {
    category: "RDB",
    fields: [
      { key: "host", labelKey: "host", required: true, placeholder: "10.0.0.20" },
      { key: "port", labelKey: "port", required: true, defaultValue: 3306, type: "number" },
      { key: "databaseName", labelKey: "database", required: true, placeholder: "mydb" },
      { key: "username", labelKey: "username", required: true },
      { key: "password", labelKey: "password", required: true, type: "password" },
    ],
    jdbcUrlTemplate: "jdbc:mariadb://{host}:{port}/{databaseName}",
  },
  DB2: {
    category: "RDB",
    fields: [
      { key: "host", labelKey: "host", required: true, placeholder: "10.0.0.20" },
      { key: "port", labelKey: "port", required: true, defaultValue: 50000, type: "number" },
      { key: "databaseName", labelKey: "database", required: true, placeholder: "MYDB" },
      { key: "username", labelKey: "username", required: true },
      { key: "password", labelKey: "password", required: true, type: "password" },
    ],
    jdbcUrlTemplate: "jdbc:db2://{host}:{port}/{databaseName}",
  },
  // ─── MPP / OLAP ────────────────────────────────
  HIVE: {
    category: "MPP",
    fields: [
      { key: "host", labelKey: "host", required: true, placeholder: "10.0.0.20" },
      { key: "port", labelKey: "port", required: true, defaultValue: 10000, type: "number" },
      { key: "databaseName", labelKey: "database", defaultValue: "default" },
      { key: "username", labelKey: "username" },
      { key: "password", labelKey: "password", type: "password" },
    ],
    jdbcUrlTemplate: "jdbc:hive2://{host}:{port}/{databaseName}",
  },
  IMPALA: {
    category: "MPP",
    fields: [
      { key: "host", labelKey: "host", required: true, placeholder: "10.0.0.20" },
      { key: "port", labelKey: "port", required: true, defaultValue: 21050, type: "number" },
      { key: "databaseName", labelKey: "database", defaultValue: "default" },
      { key: "username", labelKey: "username" },
      { key: "password", labelKey: "password", type: "password" },
    ],
    jdbcUrlTemplate: "jdbc:impala://{host}:{port}/{databaseName}",
  },
  CLICKHOUSE: {
    category: "MPP",
    fields: [
      { key: "host", labelKey: "host", required: true, placeholder: "10.0.0.20" },
      { key: "port", labelKey: "port", required: true, defaultValue: 8123, type: "number" },
      { key: "databaseName", labelKey: "database", required: true, placeholder: "default" },
      { key: "username", labelKey: "username" },
      { key: "password", labelKey: "password", type: "password" },
    ],
    jdbcUrlTemplate: "jdbc:clickhouse://{host}:{port}/{databaseName}",
  },
  STARROCKS: {
    category: "MPP",
    fields: [
      { key: "host", labelKey: "host", required: true, placeholder: "10.0.0.20" },
      { key: "port", labelKey: "port", required: true, defaultValue: 9030, type: "number" },
      { key: "databaseName", labelKey: "database", required: true, placeholder: "mydb" },
      { key: "username", labelKey: "username" },
      { key: "password", labelKey: "password", type: "password" },
    ],
    jdbcUrlTemplate: "jdbc:mysql://{host}:{port}/{databaseName}",
  },
  DORIS: {
    category: "MPP",
    fields: [
      { key: "host", labelKey: "host", required: true, placeholder: "10.0.0.20" },
      { key: "port", labelKey: "port", required: true, defaultValue: 9030, type: "number" },
      { key: "databaseName", labelKey: "database", required: true, placeholder: "mydb" },
      { key: "username", labelKey: "username" },
      { key: "password", labelKey: "password", type: "password" },
    ],
    jdbcUrlTemplate: "jdbc:mysql://{host}:{port}/{databaseName}",
  },
  // ─── NoSQL ─────────────────────────────────────
  MONGODB: {
    category: "NOSQL",
    fields: [
      { key: "host", labelKey: "host", required: true, placeholder: "10.0.0.20" },
      { key: "port", labelKey: "port", required: true, defaultValue: 27017, type: "number" },
      { key: "databaseName", labelKey: "database", required: true, placeholder: "mydb" },
      { key: "username", labelKey: "username" },
      { key: "password", labelKey: "password", type: "password" },
    ],
    noJdbcUrl: true,
  },
  REDIS: {
    category: "NOSQL",
    fields: [
      { key: "host", labelKey: "host", required: true, placeholder: "10.0.0.20" },
      { key: "port", labelKey: "port", required: true, defaultValue: 6379, type: "number" },
      { key: "password", labelKey: "password", type: "password" },
    ],
    noJdbcUrl: true,
  },
  ELASTICSEARCH: {
    category: "NOSQL",
    fields: [
      { key: "host", labelKey: "host", required: true, placeholder: "10.0.0.20" },
      { key: "port", labelKey: "port", required: true, defaultValue: 9200, type: "number" },
      { key: "username", labelKey: "username" },
      { key: "password", labelKey: "password", type: "password" },
    ],
    noJdbcUrl: true,
  },
  HBASE: {
    category: "NOSQL",
    fields: [
      { key: "host", labelKey: "zkQuorum", required: true, placeholder: "zk1:2181,zk2:2181" },
      { key: "port", labelKey: "port", defaultValue: 16000, type: "number" },
    ],
    noJdbcUrl: true,
  },
  // ─── Storage ───────────────────────────────────
  S3: {
    category: "STORAGE",
    fields: [
      { key: "host", labelKey: "endpoint", required: true, placeholder: "http://minio:9000" },
      { key: "databaseName", labelKey: "bucket", required: true, placeholder: "my-bucket" },
      { key: "username", labelKey: "accessKey", required: true },
      { key: "password", labelKey: "secretKey", required: true, type: "password" },
    ],
    noJdbcUrl: true,
  },
  HDFS: {
    category: "STORAGE",
    fields: [
      { key: "host", labelKey: "namenode", required: true, placeholder: "hdfs://namenode:8020" },
      { key: "port", labelKey: "port", defaultValue: 8020, type: "number" },
    ],
    noJdbcUrl: true,
  },
  FTP: {
    category: "STORAGE",
    fields: [
      { key: "host", labelKey: "host", required: true, placeholder: "10.0.0.20" },
      { key: "port", labelKey: "port", required: true, defaultValue: 21, type: "number" },
      { key: "username", labelKey: "username", required: true },
      { key: "password", labelKey: "password", type: "password" },
    ],
    noJdbcUrl: true,
  },
}

/** Get category display order for type selector grouping. */
export const CATEGORY_ORDER = ["RDB", "MPP", "NOSQL", "STORAGE"] as const

/** Build JDBC URL from field values using the template. */
export function buildJdbcUrl(typeCode: string, values: Record<string, string | number | null>): string | null {
  const config = DATASOURCE_TYPE_CONFIG[typeCode]
  if (!config?.jdbcUrlTemplate) return null
  let url = config.jdbcUrlTemplate
  for (const [key, val] of Object.entries(values)) {
    url = url.replace(`{${key}}`, String(val ?? ""))
  }
  return url
}
