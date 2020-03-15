/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.bbaudit.api.db.hibernate.interceptor;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.hibernate.CallbackException;
import org.hibernate.EmptyInterceptor;
import org.hibernate.type.Type;
import org.openmrs.Auditable;
import org.openmrs.Obs;
import org.openmrs.OpenmrsObject;
import org.openmrs.User;
import org.openmrs.api.context.Context;
import org.springframework.stereotype.Component;

import ventures.blockbird.data.BlockbirdAudit;

/**
 * This class looks for {@link OpenmrsObject} and {@link Auditable} that are
 * being inserted into the database. The creator and dateCreated fields are set
 * when inserting or updating objects and the fields are still null. If the
 * class is an update (instead of an insert) then the changedBy and dateChanged
 * fields are set to the current user and the current time. <br>
 * <br>
 * This class replaces the logic that was in the AuditableSaveHandler. It is
 * here so that the cascading does NOT happen for dateChanged/changedBy to child
 * OpenmrsObjects (because all handlers recurse on lists of OpenmrsObjects.
 *
 * @since 1.9
 */
@Component("zzz-BbAuditLogInterceptor")
public class BbAuditInterceptor extends EmptyInterceptor {


	private static final long serialVersionUID = 1L;

	private BlockbirdAudit bbAudit;

	protected BbAuditInterceptor() {
		Properties props = Context.getRuntimeProperties();
		
		bbAudit = BlockbirdAudit.getInstance(
			props.getProperty("blockbird.url"), 
			props.getProperty("blockbird.dbKey"),
			props.getProperty("blockbird.dbSecret")
			);
	}

	
	/**
	 * This method is only called when inserting new objects.
	 * @should return true if dateCreated was null
	 * @should return true if creator was null
	 * @should return false if dateCreated and creator was not null
	 * @should be called when saving OpenmrsObject
	 * @return true if the object got the dateCreated and creator fields set
	 * @see org.hibernate.EmptyInterceptor#onSave(java.lang.Object, java.io.Serializable,
	 *      java.lang.Object[], java.lang.String[], org.hibernate.type.Type[])
	 */
	@Override
	public boolean onSave(Object entity, Serializable id, Object[] entityCurrentState, String[] propertyNames, Type[] types) {
		User AuthenticatedUser = Context.getAuthenticatedUser(); 

		if (AuthenticatedUser != null && !AuthenticatedUser.getAllRoles().isEmpty()) {
			String auditUser = AuthenticatedUser.getUuid();
			String auditEntity = entity.getClass().getSimpleName();		
			this.bbAudit.addQuery(auditUser, AuthenticatedUser.getAllRoles().toString(), auditEntity, propertyNames, "Update", new Date(), 1);
			this.bbAudit.run();			
		}	
		return setCreatorAndDateCreatedIfNull(entity, entityCurrentState, propertyNames);
	}

	@Override
	public boolean onLoad(Object entity, Serializable id, Object[] state, String[] propertyNames, Type[] types) {

		User AuthenticatedUser = Context.getAuthenticatedUser(); 
		if (AuthenticatedUser != null && !AuthenticatedUser.getAllRoles().isEmpty()) {
			String auditUser = AuthenticatedUser.getUuid();
			String auditEntity = entity.getClass().getSimpleName();		
			this.bbAudit.addQuery(auditUser, AuthenticatedUser.getAllRoles().toString(), auditEntity, propertyNames, "Read", new Date(), 1);
		}	

		return super.onLoad(entity, id, state, propertyNames, types);
	}

	@Override
	public void onDelete(Object entity, Serializable id, Object[] state, String[] propertyNames, Type[] types) {

		User AuthenticatedUser = Context.getAuthenticatedUser(); 

		if (AuthenticatedUser != null  && !AuthenticatedUser.getAllRoles().isEmpty()) {
			String auditUser = AuthenticatedUser.getUuid();
			String auditEntity = entity.getClass().getSimpleName();		
			this.bbAudit.addQuery(auditUser, AuthenticatedUser.getAllRoles().toString(), auditEntity, propertyNames, "Delete", new Date(), 1);
		}

		super.onDelete(entity, id, state, propertyNames, types);
	}
	
	/**
	 * This class method is only called when flushing an updated dirty object, not inserting objects
	 *
	 * @return true if the object got the changedBy and dateChanged fields set
	 * @should set the dateChanged field
	 * @should set the changedBy field
	 * @should be called when saving an Auditable
	 * @should not enter into recursion on entity
	 * @see org.hibernate.EmptyInterceptor#onFlushDirty(java.lang.Object, java.io.Serializable,
	 *      java.lang.Object[], java.lang.Object[], java.lang.String[], org.hibernate.type.Type[])
	 */
	
	@Override
	public boolean onFlushDirty(Object entity, Serializable id, Object[] currentState, Object[] previousState,
	        String[] propertyNames, Type[] types) throws CallbackException {
		boolean objectWasChanged;
		
		objectWasChanged = setCreatorAndDateCreatedIfNull(entity, currentState, propertyNames);
		
		if (entity instanceof Auditable && propertyNames != null) {
			System.out.println("Setting changed by fields on " + entity.getClass());
						
			Map<String, Object> propertyValues = getPropertyValuesToUpdate();
			if (entity instanceof Obs) {
				propertyValues.remove("changedBy");
				propertyValues.remove("dateChanged");
			}
			objectWasChanged = changeProperties(currentState, propertyNames, objectWasChanged, propertyValues, false);
		}
		return objectWasChanged;
	}
	
	/**
	 * Sets the creator and dateCreated fields to the current user and the current time if they are
	 * null.
	 * if is a Person Object, sets the personCreator and personDateCreated fields to the current user and the current time
	 * if they are null.
	 *
	 * @param entity
	 * @param currentState
	 * @param propertyNames
	 * @return true if creator and dateCreated were changed
	 */
	private boolean setCreatorAndDateCreatedIfNull(Object entity, Object[] currentState, String[] propertyNames) {
		
		boolean objectWasChanged = false;
		
		if (entity instanceof OpenmrsObject) {
						
			Map<String, Object> propertyValues = getPropertyValuesToSave();
			objectWasChanged = changeProperties(currentState, propertyNames, objectWasChanged, propertyValues, true);
		}
		return objectWasChanged;
	}
	
	private boolean changeProperties(Object[] currentState, String[] propertyNames, boolean objectWasChanged,
	        Map<String, Object> propertyValues, Boolean setNullOnly) {
		
		for (Map.Entry<String, Object> e : propertyValues.entrySet()) {
			if (changePropertyValue(currentState, propertyNames, e.getKey(), e.getValue(), setNullOnly)) {
				objectWasChanged = true;
			}
		}
		return objectWasChanged;
	}
	
	private Map<String, Object> getPropertyValuesToSave() {
		Map<String, Object> propertyValues = new HashMap<String, Object>();
		propertyValues.put("creator", Context.getAuthenticatedUser());
		propertyValues.put("dateCreated", new Date());
		propertyValues.put("personCreator", Context.getAuthenticatedUser());
		propertyValues.put("personDateCreated", new Date());
		return propertyValues;
	}
	
	private Map<String, Object> getPropertyValuesToUpdate() {
		Map<String, Object> propertyValues = new HashMap<String, Object>();
		propertyValues.put("changedBy", Context.getAuthenticatedUser());
		propertyValues.put("dateChanged", new Date());
		propertyValues.put("personChangedBy", Context.getAuthenticatedUser());
		propertyValues.put("personDateChanged", new Date());
		return propertyValues;
	}
	
	/**
	 * Sets the property to the given value.
	 *
	 * @param currentState
	 * @param propertyNames
	 * @param propertyToSet
	 * @param value
	 * @param setNullOnly
	 * @return true if the property was changed
	 */
	private boolean changePropertyValue(Object[] currentState, String[] propertyNames, String propertyToSet, Object value,
	        boolean setNullOnly) {
		
		int index = Arrays.asList(propertyNames).indexOf(propertyToSet);
		
		if (value == null) {
			return false;
		}
		
		if (index >= 0 && (currentState[index] == null || !setNullOnly) && !value.equals(currentState[index])) {
			currentState[index] = value;
			return true;
		}
		return false;
	}
}
