/*
 * Copyright (c) 2016-2088, fastquery.org and/or its affiliates. All rights reserved.
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 * For more information, please see http://www.fastquery.org/.
 * 
 */

package org.fastquery.test;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import org.fastquery.core.QueryContext;
import org.fastquery.core.Repository;
import org.fastquery.core.RepositoryException;
import org.fastquery.filter.SkipFilter;
import org.fastquery.struct.SQLValue;

/**
 * 
 * @author mei.sir@aliyun.cn
 */
public class FastQueryTestRule implements TestRule {

	private static final Logger LOG = LoggerFactory.getLogger(FastQueryTestRule.class);
	private SQLValue sqlValue;
	private List<SQLValue> sqlValues;

	private void proxy(Statement base, Description description) throws NoSuchFieldException, IllegalAccessException {
		Object testTarget = getTestTarget(base);
		LOG.debug("SkipFilter:" + description.getAnnotation(SkipFilter.class));
		Class<?> clazz = description.getTestClass();
		List<Field> fList = new ArrayList<>();
		Field[] fields = clazz.getDeclaredFields();
		for (Field field : fields) {
			if (Repository.class.isAssignableFrom(field.getType())) {
				field.setAccessible(true);
				fList.add(field);
			}
		}
		fields = clazz.getFields();
		for (Field field : fields) {
			if (Repository.class.isAssignableFrom(field.getType())) {
				field.setAccessible(true);
				fList.add(field);
			}
		}

		for (Field field : fList) {
			Repository repository = (Repository) field.get(testTarget);
			Class<?> interfaceClazz = repository.getClass().getInterfaces()[0];
			// 代理repository这个对象
			field.set(testTarget, Proxy.newProxyInstance(this.getClass().getClassLoader(), new Class<?>[] { interfaceClazz },
					new RepositoryInvocationHandler(repository, this, description)));
		}
	}

	private Object getTestTarget(Statement base) throws NoSuchFieldException, IllegalAccessException {
		if (base instanceof org.junit.internal.runners.statements.ExpectException) {
			Field nextField = base.getClass().getDeclaredField("next");
			nextField.setAccessible(true);
			// 获取目标对象
			Object nextBase = nextField.get(base);

			Field targetField = nextBase.getClass().getDeclaredField("target");
			targetField.setAccessible(true);
			// 获取目标对象
			return targetField.get(nextBase);
		} else {
			Field targetField = base.getClass().getDeclaredField("target");
			targetField.setAccessible(true);
			// 获取目标对象
			return targetField.get(base);
		}
	}

	@Override
	public Statement apply(Statement base, Description description) {

		return new Statement() {
			@Override
			public void evaluate() throws Throwable {
				try {
					proxy(base, description);
					LOG.debug("{} --------------------------------------------开始执行,当前线程: {}", description.getMethodName(), Thread.currentThread());
					base.evaluate();
				} catch (Throwable e) {
					throw new RepositoryException(e);
				} finally {
					after(description);
				}
			}
		};
	}

	/**
	 * 若DB操作只有一条SQL,就使用这个方法获取
	 * 
	 * @return
	 */
	public SQLValue getSQLValue() {
		return getListSQLValue().get(0);
	}

	/**
	 * 获取DB操作后所产生的SQL语句
	 * 
	 * @return SQL语句
	 */
	public List<SQLValue> getListSQLValue() {
		if (sqlValue != null) {
			List<SQLValue> ls = new ArrayList<>();
			ls.add(sqlValue);
			return ls;
		} else {
			return sqlValues;
		}
	}

	private void after(Description description) throws SQLException {
		LOG.debug("{} --------------------------------------------已经结束,当前线程:{}", description.getMethodName(), Thread.currentThread());
		QueryContext context = QueryContextHelper.getQueryContext();
		if (context != null) {
			Rollback rollback = description.getAnnotation(Rollback.class);
			if (rollback == null || rollback.value()) {
				QueryContext.getConnection().rollback();
				LOG.info("事务已经回滚");
			} else {
				QueryContext.getConnection().commit();
				LOG.info("事务已经提交");
			}
			QueryContext.forceClear();
		}
	}
}