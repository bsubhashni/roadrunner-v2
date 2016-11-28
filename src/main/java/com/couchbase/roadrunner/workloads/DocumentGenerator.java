package com.couchbase.roadrunner.workloads;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import com.couchbase.roadrunner.customConverter.ByteJsonDocument;
import com.couchbase.roadrunner.customConverter.JacksonConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DocumentGenerator {

	private static final Logger LOGGER =
			LoggerFactory.getLogger(DocumentGenerator.class.getName());

	private JacksonConverter converter;
	private String className;

	public DocumentGenerator(String className) {
		this.className = className;
		this.converter = new JacksonConverter();
	}

	public ByteJsonDocument getDocument(String id) {
		try {
			//todo use config and populate class realistically
			Class<?> clazz = Class.forName("com.couchbase.roadrunner.sampleClasses." + className);
			Constructor<?> ctor = clazz.getConstructors()[0];
			Object instance = ctor.newInstance();
			Method method = clazz.getDeclaredMethod("populate");
			method.invoke(instance);
			return ByteJsonDocument.create(id, this.converter.toBytes(instance));
			} catch (Exception ex) {
				LOGGER.error("Unable to generate document " + ex);
		}
		return null;
	}
}
