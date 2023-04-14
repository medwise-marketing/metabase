(ns metabase.metabot.metabot-util-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer :all]
   [metabase.db.query :as mdb.query]
   [metabase.lib.native :as lib-native]
   [metabase.metabot-test :as metabot-test]
   [metabase.metabot.settings :as metabot-settings]
   [metabase.metabot.util :as metabot-util]
   [metabase.models :refer [Card Database]]
   [metabase.query-processor :as qp]
   [metabase.test :as mt]
   [metabase.test.util :as tu]
   [metabase.util :as u]
   [toucan2.core :as t2]
   [toucan2.tools.with-temp :as t2.with-temp]))

(deftest normalize-name-test
  (testing "Testing basic examples of how normalize-name should work"
    (is (= "A_B_C"
           (metabot-util/normalize-name "A B C")))
    (is (= "PEOPLE_DATA_IS_FUN_TEST"
           (metabot-util/normalize-name "People --> Data.Is.FUN __ TEST   ")))
    (is (= "PERSON_PLACE_OR_THING"
           (metabot-util/normalize-name "Person, Place, or Thing")))
    (is (= "PEOPLE_USER_ID"
           (metabot-util/normalize-name "People - User → ID")))))

(deftest create-table-ddl-test
  (testing "Testing the test-create-table-ddl function"
    (let [model {:sql_name        "TABLE"
                 :result_metadata (mapv
                                    (fn [{:keys [display_name] :as m}]
                                      (assoc m
                                        :sql_name
                                        (metabot-util/normalize-name display_name)))
                                    [{:display_name "Name"
                                      :base_type    :type/Text}
                                     {:display_name "Frooby"
                                      :base_type    :type/Boolean}
                                     {:display_name "Age"
                                      :base_type    :type/Integer}
                                     ;; Low cardinality items should show as enumerated
                                     {:display_name    "Sizes"
                                      :base_type       :type/Integer
                                      :possible_values [1 2 3]}
                                     {:display_name    "BigCardinality"
                                      :base_type       :type/Integer}])}]
      (is (= (mdb.query/format-sql
               (str
                 "create type SIZES_t as enum '1', '2', '3';"
                 "CREATE TABLE \"TABLE\" ('NAME' TEXT,'FROOBY' BOOLEAN, 'AGE' INTEGER, 'SIZES' 'SIZES_t','BIGCARDINALITY' INTEGER)"))
             (mdb.query/format-sql
               (#'metabot-util/create-table-ddl model)))))))

(deftest denormalize-field-cardinality-test
  (testing "Ensure enum-cardinality-threshold is respected in model denormalization"
    (mt/dataset sample-dataset
      (mt/with-temp* [Card [model
                            {:dataset_query
                             {:database (mt/id)
                              :type     :query
                              :query    {:source-table (mt/id :people)}}
                             :dataset true}]]
        (tu/with-temporary-setting-values [metabot-settings/enum-cardinality-threshold 0]
          (let [{:keys [result_metadata]} (metabot-util/denormalize-model model)]
            (zero? (count (filter :possible_values result_metadata)))))
        (tu/with-temporary-setting-values [metabot-settings/enum-cardinality-threshold 10]
          (let [{:keys [result_metadata]} (metabot-util/denormalize-model model)]
            (= 1 (count (filter :possible_values result_metadata)))))
        (tu/with-temporary-setting-values [metabot-settings/enum-cardinality-threshold 50]
          (let [{:keys [result_metadata]} (metabot-util/denormalize-model model)]
            (= 2 (count (filter :possible_values result_metadata)))))))))

(deftest denormalize-model-test
  (testing "Basic denormalized model test"
    (mt/dataset sample-dataset
      (mt/with-temp* [Card [model
                            {:dataset_query
                             {:database (mt/id)
                              :type     :query
                              :query    {:source-table (mt/id :people)}}
                             :dataset true}]]
                     (let [{:keys [create_table_ddl inner_query sql_name result_metadata]} (metabot-util/denormalize-model model)]
                       (is (string? create_table_ddl))
                       (is (string? sql_name))
                       (is (string? inner_query))
                       (is
                         (= #{"Affiliate"
                              "Facebook"
                              "Google"
                              "Organic"
                              "Twitter"}
                            (->> result_metadata
                                 (some (fn [{:keys [sql_name] :as rsmd}] (when (= "SOURCE" sql_name) rsmd)))
                                 :possible_values
                                 set))))))))

(deftest denormalize-database-test
  (testing "Basic denormalized database test"
    (mt/dataset sample-dataset
      (mt/with-temp* [Card [_
                            {:dataset_query
                             {:database (mt/id)
                              :type     :query
                              :query    {:source-table (mt/id :orders)}}
                             :dataset true}]]
                     (let [database (t2/select-one Database :id (mt/id))
                           {:keys [models model_json_summary sql_name]} (metabot-util/denormalize-database database)]
                       (is (=
                             (count (t2/select Card :database_id (mt/id) :dataset true))
                             (count models)))
                       (is (string? model_json_summary))
                       (is (string? sql_name)))))))

(deftest create-prompt-test
  (testing "We can do prompt lookup and interpolation"
    (with-redefs [metabot-util/*prompt-templates* (constantly metabot-test/test-prompt-templates)]
      (let [prompt (metabot-util/create-prompt
                    {:model       {:sql_name         "TEST_MODEL"
                                   :create_table_ddl "CREATE TABLE TEST_MODEL"}
                     :user_prompt "Find my data"
                     :prompt_task :infer_sql})]
        (= {:prompt_template   "infer_sql",
            :version           "0001",
            :messages          [{:role "system", :content "The system prompt"}
                                {:role "assistant", :content "TEST_MODEL"}
                                {:role "assistant", :content "CREATE TABLE TEST_MODEL"}
                                {:role "user", :content "A 'Find my data'"}],
            :message_templates [{:role "system", :content "The system prompt"}
                                {:role "assistant", :content "%%MODEL:SQL_NAME%%"}
                                {:role "assistant", :content "%%MODEL:CREATE_TABLE_DDL%%"}
                                {:role "user", :content "A '%%USER_PROMPT%%'"}]}
           prompt)))))

(deftest extract-sql-test
  (testing "Test that we detect a simple SQL string"
    (let [sql "SELECT * FROM SOMETHING"]
      (is (= (mdb.query/format-sql sql)
             (metabot-util/extract-sql sql))))
    (let [sql (u/lower-case-en "SELECT * FROM SOMETHING")]
      (is (= (mdb.query/format-sql sql)
             (metabot-util/extract-sql sql)))))
  (testing "Test that we detect SQL embedded in markdown"
    (let [sql     "SELECT * FROM SOMETHING"
          bot-str (format "kmfeasf fasel;f fasefes; fasef;o ```%s```feafs feass" sql)]
      (is (= (mdb.query/format-sql sql)
             (metabot-util/extract-sql bot-str)))))
  (testing "Test that we detect SQL embedded in markdown with language hint"
    (let [sql     "SELECT * FROM SOMETHING"
          bot-str (format "kmfeasf fasel;f fasefes; fasef;o ```sql%s```feafs feass" sql)]
      (is (= (mdb.query/format-sql sql)
             (metabot-util/extract-sql bot-str))))))

(deftest bot-sql->final-sql-test
  (testing "A simple test of interpolation of denormalized data with bot sql"
    (is (= "WITH MY_MODEL AS (SELECT * FROM {{#123}} AS INNER_QUERY) SELECT * FROM MY_MODEL"
           (metabot-util/bot-sql->final-sql
             {:inner_query "SELECT * FROM {{#123}} AS INNER_QUERY"
              :sql_name    "MY_MODEL"}
             "SELECT * FROM MY_MODEL")))))

(deftest ensure-generated-sql-works-test
  (testing "Ensure the generated sql (including creating a CTE and querying from it) is valid (i.e. produces a result)."
    (mt/test-drivers #{:h2 :postgres :redshift}
      (mt/dataset sample-dataset
        (mt/with-temp* [Card [{model-name :name :as model}
                              {:dataset_query
                               {:database (mt/id)
                                :type     :query
                                :query    {:source-table (mt/id :people)}}
                               :dataset true}]]
          (let [{:keys [inner_query] :as denormalized-model} (metabot-util/denormalize-model model)
                sql     (metabot-util/bot-sql->final-sql
                         denormalized-model
                         (format "SELECT * FROM %s" model-name))
                results (qp/process-query
                         {:database (mt/id)
                          :type     "native"
                          :native   {:query         sql
                                     :template-tags (update-vals
                                                     (lib-native/template-tags inner_query)
                                                     (fn [m] (update m :id str)))}})]
            (is (some? (seq (get-in results [:data :rows]))))))))))

(deftest create-database-ddl-test
  (testing "Ensure the generated pseudo-ddl contains the expected tables and enums."
    (mt/dataset sample-dataset
      (tu/with-temporary-setting-values [metabot-settings/enum-cardinality-threshold 50]
        (let [{:keys [create_database_ddl]} (->> (metabot-util/denormalize-database {:id (mt/id)})
                                                 metabot-util/add-pseudo-database-ddl)]
          (is (str/includes? create_database_ddl "create type PRODUCTS_CATEGORY_t as enum 'Doohickey', 'Gadget', 'Gizmo', 'Widget';"))
          (is (str/includes? create_database_ddl "create type PEOPLE_STATE_t as enum 'AK', 'AL', 'AR', 'AZ', 'CA', 'CO', 'CT',"))
          (is (str/includes? create_database_ddl "create type PEOPLE_SOURCE_t as enum 'Affiliate', 'Facebook', 'Google', 'Organic', 'Twitter';"))
          (is (str/includes? create_database_ddl "create type REVIEWS_RATING_t as enum '1', '2', '3', '4', '5';"))
          (is (str/includes? create_database_ddl "CREATE TABLE \"PRODUCTS\" ("))
          (is (str/includes? create_database_ddl "CREATE TABLE \"ORDERS\" ("))
          (is (str/includes? create_database_ddl "CREATE TABLE \"PEOPLE\" ("))
          (is (str/includes? create_database_ddl "CREATE TABLE \"REVIEWS\" (")))))))

(deftest inner-query-test
  (testing "Ensure that a dataset-based query contains expected AS aliases"
    (mt/dataset sample-dataset
      (t2.with-temp/with-temp
        [Card orders-model {:name    "Orders Model"
                            :dataset_query
                            {:database (mt/id)
                             :type     :query
                             :query    {:source-table (mt/id :orders)}}
                            :dataset true}]
        (let [{:keys [column_aliases inner_query create_table_ddl sql_name]} (metabot-util/denormalize-model orders-model)]
          (is (= 9 (count (re-seq #"\s+AS\s+" column_aliases))))
          (is (= 10 (count (re-seq #"\s+AS\s+" inner_query))))
          (is (= (mdb.query/format-sql
                   (str/join
                     [(format "CREATE TABLE \"%s\" (" sql_name)
                      "'ID' BIGINTEGER,"
                      "'USER_ID' INTEGER,"
                      "'PRODUCT_ID' INTEGER,"
                      "'SUBTOTAL' FLOAT,"
                      "'TAX' FLOAT,"
                      "'TOTAL' FLOAT,"
                      "'DISCOUNT' FLOAT,"
                      "'CREATED_AT' DATETIMEWITHLOCALTZ,"
                      "'QUANTITY' INTEGER)"]))
                 create_table_ddl)))))))

(deftest native-inner-query-test
  (testing "A SELECT * will produce column all column names in th resulting DDLs"
    (mt/dataset sample-dataset
      (let [q (mt/native-query {:query "SELECT * FROM ORDERS;"})
            result-metadata (get-in (qp/process-query q) [:data :results_metadata :columns])]
        (t2.with-temp/with-temp
          [Card orders-model {:name          "Orders Model"
                              :dataset_query q
                              :result_metadata result-metadata
                              :dataset       true}]
          (let [{:keys [column_aliases inner_query create_table_ddl sql_name]} (metabot-util/denormalize-model orders-model)]
            (is (= (mdb.query/format-sql
                     (format "SELECT %s FROM {{#%s}} AS INNER_QUERY" column_aliases (:id orders-model)))
                   inner_query))
            (is (= (mdb.query/format-sql
                     (str/join
                       [(format "CREATE TABLE \"%s\" (" sql_name)
                        "'ID' BIGINTEGER,"
                        "'USER_ID' INTEGER,"
                        "'PRODUCT_ID' INTEGER,"
                        "'SUBTOTAL' FLOAT,"
                        "'TAX' FLOAT,"
                        "'TOTAL' FLOAT,"
                        "'DISCOUNT' FLOAT,"
                        "'CREATED_AT' DATETIMEWITHLOCALTZ,"
                        "'QUANTITY' INTEGER)"]))
                   (mdb.query/format-sql create_table_ddl)))
            create_table_ddl)))))
  (testing "A SELECT of columns will produce those column names in th resulting DDLs"
    (mt/dataset sample-dataset
      (let [q (mt/native-query {:query "SELECT TOTAL, QUANTITY, TAX, CREATED_AT FROM ORDERS;"})
            result-metadata (get-in (qp/process-query q) [:data :results_metadata :columns])]
        (t2.with-temp/with-temp
          [Card orders-model {:name          "Orders Model"
                              :dataset_query q
                              :result_metadata result-metadata
                              :dataset       true}]
          (let [{:keys [column_aliases inner_query create_table_ddl sql_name]} (metabot-util/denormalize-model orders-model)]
            (is (= (mdb.query/format-sql
                     (format "SELECT %s FROM {{#%s}} AS INNER_QUERY" column_aliases (:id orders-model)))
                   inner_query))
            (is (= (mdb.query/format-sql
                     (str/join
                       [(format "CREATE TABLE \"%s\" (" sql_name)
                        "'TOTAL' FLOAT,"
                        "'QUANTITY' INTEGER,"
                        "'TAX' FLOAT,"
                        "'CREATED_AT' DATETIMEWITHLOCALTZ)"]))
                   (mdb.query/format-sql create_table_ddl)))
            create_table_ddl)))))
  (testing "Duplicate native column aliases will be deduplicated"
    (mt/dataset sample-dataset
      (let [q (mt/native-query {:query "SELECT TOTAL AS X, QUANTITY AS X FROM ORDERS;"})
            result-metadata (get-in (qp/process-query q) [:data :results_metadata :columns])]
        (t2.with-temp/with-temp
          [Card orders-model {:name          "Orders Model"
                              :dataset_query q
                              :result_metadata result-metadata
                              :dataset       true}]
          (let [{:keys [column_aliases inner_query create_table_ddl sql_name]} (metabot-util/denormalize-model orders-model)]
            (is (= (mdb.query/format-sql
                     (format "SELECT %s FROM {{#%s}} AS INNER_QUERY" column_aliases (:id orders-model)))
                   inner_query))
            (is (= (mdb.query/format-sql
                     (str/join
                       [(format "CREATE TABLE \"%s\" (" sql_name)
                        "'X' FLOAT,"
                        "'X_2' INTEGER)"]))
                   (mdb.query/format-sql create_table_ddl)))))))))

(deftest inner-query-with-joins-test
  (testing "Models with joins work"
    (mt/dataset sample-dataset
      (t2.with-temp/with-temp
        [Card joined-model {:dataset     true
                            :database_id (mt/id)
                            :query_type  :query
                            :dataset_query
                            (mt/mbql-query orders
                              {:fields [$total &products.products.category]
                               :joins  [{:source-table $$products
                                         :condition    [:= $product_id &products.products.id]
                                         :strategy     :left-join
                                         :alias        "products"}]})}]
        (let [{:keys [column_aliases create_table_ddl sql_name]} (metabot-util/denormalize-model joined-model)]
          (is (= "\"TOTAL\" AS TOTAL, \"products__CATEGORY\" AS PRODUCTS_CATEGORY"
                 column_aliases))
          (is (= (mdb.query/format-sql
                   (str/join
                     ["create type PRODUCTS_CATEGORY_t as enum 'Doohickey', 'Gadget', 'Gizmo', 'Widget';"
                      (format "CREATE TABLE \"%s\" (" sql_name)
                      "'TOTAL' FLOAT,"
                      "'PRODUCTS_CATEGORY' 'PRODUCTS_CATEGORY_t')"]))
                 (mdb.query/format-sql create_table_ddl)))))))
  (testing "A model with joins on the same table will produce distinct aliases"
    (mt/dataset sample-dataset
      (t2.with-temp/with-temp
        [Card joined-model {:dataset     true
                            :database_id (mt/id)
                            :query_type  :query
                            :dataset_query
                            (mt/mbql-query products
                              {:fields [$id $category &self.products.category]
                               :joins  [{:source-table $$products
                                         :condition    [:= $id &self.products.id]
                                         :strategy     :left-join
                                         :alias        "self"}]})}]
        (let [{:keys [column_aliases create_table_ddl sql_name]} (metabot-util/denormalize-model joined-model)]
          (is (= "\"ID\" AS ID, \"CATEGORY\" AS CATEGORY, \"self__CATEGORY\" AS SELF_CATEGORY"
                 column_aliases))
          (is (= (mdb.query/format-sql
                   (str/join
                     ["create type CATEGORY_t as enum 'Doohickey', 'Gadget', 'Gizmo', 'Widget';"
                      "create type SELF_CATEGORY_t as enum 'Doohickey', 'Gadget', 'Gizmo', 'Widget';"
                      (format "CREATE TABLE \"%s\" (" sql_name)
                      "'ID' BIGINTEGER,"
                      "'CATEGORY' 'CATEGORY_t',"
                      "'SELF_CATEGORY' 'SELF_CATEGORY_t')"]))
                 (mdb.query/format-sql create_table_ddl))))))))

(deftest inner-query-with-aggregations-test
  (testing "A model with aggregations will produce column names only (no AS aliases)"
    (mt/dataset sample-dataset
      (t2.with-temp/with-temp
        [Card aggregated-model {:dataset     true
                                :database_id (mt/id)
                                :query_type  :query
                                :dataset_query
                                (mt/mbql-query orders
                                  {:aggregation [[:sum $total]]
                                   :breakout    [$user_id]})}]
        (let [{:keys [column_aliases inner_query create_table_ddl sql_name]} (metabot-util/denormalize-model aggregated-model)]
          (is (= (mdb.query/format-sql
                   (format "SELECT USER_ID, SUM_OF_TOTAL FROM {{#%s}} AS INNER_QUERY" (:id aggregated-model)))
                 inner_query))
          (is (= "USER_ID, SUM_OF_TOTAL" column_aliases))
          (is (= (format "CREATE TABLE \"%s\" ('USER_ID' INTEGER, 'SUM_OF_TOTAL' FLOAT)" sql_name)
                 create_table_ddl))
          create_table_ddl)))))

(deftest inner-query-name-collisions-test
  (testing "When column names collide, each conflict is disambiguated with an _X postfix"
    (mt/dataset sample-dataset
      (t2.with-temp/with-temp
        [Card orders-model {:name    "Orders Model"
                            :dataset_query
                            {:database (mt/id)
                             :type     :query
                             :query    {:source-table (mt/id :orders)}}
                            :dataset true}]
        (let [orders-model (update orders-model :result_metadata
                                   (fn [v]
                                     (map #(assoc % :display_name "ABC") v)))
              {:keys [column_aliases create_table_ddl]} (metabot-util/denormalize-model orders-model)]
          (is (= 9 (count (re-seq #"ABC(?:_\d+)?" column_aliases))))
          ;; Ensure that the same aliases are used in the create table ddl
          (is (= 9 (count (re-seq #"ABC" create_table_ddl))))))))
  (testing "Models with name collisions across joins are also correctly disambiguated"
    (mt/dataset sample-dataset
      (t2.with-temp/with-temp
        [Card model {:dataset     true
                     :database_id (mt/id)
                     :query_type  :query
                     :dataset_query
                     (mt/mbql-query orders
                       {:fields [$total &products.products.category &self.products.category]
                        :joins  [{:source-table $$products
                                  :condition    [:= $product_id &products.products.id]
                                  :strategy     :left-join
                                  :alias        "products"}
                                 {:source-table $$products
                                  :condition    [:= $id &self.products.id]
                                  :strategy     :left-join
                                  :alias        "self"}]})}]
        (let [model (update model :result_metadata
                            (fn [v]
                              (map #(assoc % :display_name "FOO") v)))
              {:keys [column_aliases create_table_ddl]} (metabot-util/denormalize-model model)]
          (is (= "\"TOTAL\" AS FOO, \"products__CATEGORY\" AS FOO_2, \"self__CATEGORY\" AS FOO_3"
                 column_aliases))
          ;; Ensure that the same aliases are used in the create table ddl
          ;; 7 = 3 for the column names + 2 for the type creation + 2 for the type references
          (is (= 7 (count (re-seq #"FOO" create_table_ddl)))))))))

(deftest deconflicting-aliases-test
  (testing "Test sql_name generation deconfliction:
            - Potentially conflicting names are retained
            - As conflicts occur, _X is appended to each alias in increasing order, skipping existing aliases"
    (is
      (= [{:display_name "ABC", :sql_name "ABC"}
          {:display_name "AB", :sql_name "AB"}
          {:display_name "A B C", :sql_name "A_B_C"}
          {:display_name "ABC", :sql_name "ABC_2"}
          {:display_name "ABC_1", :sql_name "ABC_1"}
          {:display_name "ABC", :sql_name "ABC_3"}]
         (:result_metadata
           (#'metabot-util/add-sql-names
             {:result_metadata
              [{:display_name "ABC"}
               {:display_name "AB"}
               {:display_name "A B C"}
               {:display_name "ABC"}
               {:display_name "ABC_1"}
               {:display_name "ABC"}]}))))))
