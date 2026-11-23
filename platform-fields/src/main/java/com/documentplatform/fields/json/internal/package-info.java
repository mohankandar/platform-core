/**
 * The strict JSON of the field list file and the field value delivery. Internal: used by this module's {@code file}
 * and {@code delivery} packages only; it may change in any release and is not supported for apps. Its
 * {@code JsonException} never reaches a caller: the readers turn it into {@code FieldFileException} or a
 * {@code DeliveryProblem}.
 */
package com.documentplatform.fields.json.internal;
