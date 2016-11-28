package com.couchbase.roadrunner.sampleClasses;
import com.fasterxml.jackson.annotation.JsonProperty;

public class Simple {

	@JsonProperty
	private String property0;

	@JsonProperty
	private String property1;

	@JsonProperty
	private String property2;

	@JsonProperty
	private String property3;

	@JsonProperty
	private String property4;

	@JsonProperty
	private String property5;

	@JsonProperty
	private String property6;

	@JsonProperty
	private String property7;

	@JsonProperty
	private String property8;

	@JsonProperty
	private String property9;

	public Simple() {
	}

	public void populate() {
		property0 = "";
		property1 = "";
		property2 = "";
		property3 = "";
		property4 = "";
		property5 = "";
		property6 = "";
		property7 = "";
		property8 = "";
		property9 = "";
		for(int i = 0; i < 100; i++){
			char nextchar =  (char)((Math.random() * 26 + 0) +97);
			property0 += nextchar;
			property1 += nextchar;
			property2 += nextchar;
			property3 += nextchar;
			property4 += nextchar;
			property5 += nextchar;
			property6 += nextchar;
			property7 += nextchar;
			property8 += nextchar;
			property9 += nextchar;
		}
	}
}
