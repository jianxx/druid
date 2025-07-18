/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Hand picked from https://druid.apache.org/docs/latest/querying/sql.html

export const SQL_KEYWORDS = [
  'ALL',
  'AND',
  'ANY',
  'AS',
  'ASC',
  'BETWEEN',
  'BOTH',
  'CASE',
  'CENTURY',
  'CLUSTERED BY',
  'CROSS',
  'CSV',
  'CUBE',
  'CURRENT',
  'DAY',
  'DAYS',
  'DECADE',
  'DESC',
  'DISTINCT',
  'DOW',
  'DOY',
  'ELSE',
  'END',
  'EPOCH',
  'ESCAPE',
  'EXPLAIN PLAN FOR',
  'EXTEND',
  'FETCH',
  'FILTER',
  'FIRST',
  'FOLLOWING',
  'FROM',
  'FULL',
  'GROUP BY',
  'GROUPING SETS',
  'HAVING',
  'HOUR',
  'HOURS',
  'IN',
  'INNER',
  'INSERT INTO',
  'INTERVAL',
  'IS',
  'ISODOW',
  'ISOYEAR',
  'JOIN',
  'KEY',
  'LEADING',
  'LEFT',
  'LIKE',
  'LIMIT',
  'MATCHED',
  'MERGE INTO',
  'MILLENNIUM',
  'MILLISECOND',
  'MINUTE',
  'MINUTES',
  'MONTH',
  'MONTHS',
  'NATURAL',
  'NEXT',
  'NOT',
  'OFFSET',
  'ON',
  'ONLY',
  'OR',
  'ORDER BY',
  'OUTER',
  'OVER',
  'OVERWRITE',
  'PARTITION BY',
  'PARTITIONED BY',
  'PIVOT',
  'PRECEDING',
  'QUARTER',
  'QUARTERS',
  'RANGE',
  'REPLACE INTO',
  'RETURNING',
  'RIGHT',
  'ROLLUP',
  'ROW',
  'ROWS',
  'SECOND',
  'SECONDS',
  'SELECT',
  'SET',
  'SIMILAR',
  'SOME',
  'SYMMETRIC',
  'THEN',
  'TIME',
  'TIMESTAMP',
  'TO',
  'TRAILING',
  'UNBOUNDED',
  'UNION ALL',
  'UNPIVOT',
  'UPDATE SET',
  'USING',
  'VALUE',
  'VALUES',
  'WEEK',
  'WEEKS',
  'WHEN',
  'WHERE',
  'WINDOW',
  'WITH',
  'YEAR',
  'YEARS',
];

export const SQL_CONSTANTS = ['NULL', 'FALSE', 'TRUE'];

export const SQL_DYNAMICS = [
  'CURRENT_TIMESTAMP',
  'CURRENT_DATE',
  'LOCALTIME',
  'LOCALTIMESTAMP',
  'CURRENT_TIME',
];
