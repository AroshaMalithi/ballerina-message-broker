/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */

package io.ballerina.messaging.broker.core.selector;

import io.ballerina.messaging.broker.core.Metadata;

/**
 * Implementation of a  expression. Here we calculate the subtraction of two expressions and evaluate to a object value.
 */


public class Subtraction implements Expression<Metadata> {

    private final Expression<Metadata> left;

    private final Expression<Metadata> right;

    public Subtraction (Expression<Metadata> left , Expression<Metadata> right) {
        this.left = left;
        this.right = right;
    }

    @Override
    public Object evaluate(Metadata metadata) {
        Object leftValue = left.evaluate(metadata);
        Object rightValue = right.evaluate(metadata);
        if (leftValue == null || rightValue == null) {
            return null;
        }
        ConvertAndCompare con = new ConvertAndCompare();
        double z = con.convertToDouble(leftValue) - con.convertToDouble(rightValue);
        String sub = String.valueOf(z);
        Number value;
        value = Double.valueOf(sub);

        return value;
    }

}
