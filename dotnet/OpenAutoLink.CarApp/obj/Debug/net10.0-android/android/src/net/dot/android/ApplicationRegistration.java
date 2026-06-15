package net.dot.android;

public class ApplicationRegistration {

	public static android.content.Context Context;

	public static void registerApplications ()
	{
		// Application and Instrumentation ACWs must be registered first.
		mono.android.Runtime.register ("OpenAutoLink.CarApp.OalApplication, OpenAutoLink.CarApp, Version=0.1.0.0, Culture=neutral, PublicKeyToken=null", crc644f562033b1739e14.OalApplication.class, crc644f562033b1739e14.OalApplication.__md_methods);
		
	}
}
