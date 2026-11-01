/*
 * Copyright 2025 FormDev Software GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.formdev.flatlaf.ui;

import java.awt.Window;
import java.lang.reflect.Method;

/**
 * Access to the <a href="https://github.com/JetBrains/JetBrainsRuntime">JetBrains Runtime</a> API.
 * <p>
 * The API is used via reflection only. FlatLaf has no compile-time dependency on {@code jbr-api}.
 * Each service is resolved at most once per JVM (in the class initializer of its nested holder class)
 * and never on other Java runtimes, where every method answers that the service is not available.
 * <p>
 * <b>Note</b>: This is private API. Do not use!
 *
 * @since 3.8
 */
public class FlatJBRSupport
{
	private FlatJBRSupport() {}

	//---- window move --------------------------------------------------------

	/**
	 * Checks whether {@code com.jetbrains.WindowMove} is available.
	 */
	public static boolean isWindowMoveSupported() {
		return WindowMove.START_MOVING != null;
	}

	/**
	 * Invokes {@code com.jetbrains.WindowMove.startMovingTogetherWithMouse(Window,int)}.
	 *
	 * @throws ReflectiveOperationException if the invocation failed
	 */
	public static void startMovingTogetherWithMouse( Window window, int mouseButton )
		throws ReflectiveOperationException
	{
		WindowMove.START_MOVING.invoke( WindowMove.SERVICE, window, mouseButton );
	}

	//---- rounded corners ----------------------------------------------------

	/**
	 * Checks whether {@code com.jetbrains.RoundedCornersManager} is available.
	 */
	public static boolean isRoundedCornersSupported() {
		return RoundedCorners.SET_ROUNDED_CORNERS != null;
	}

	/**
	 * Invokes {@code com.jetbrains.RoundedCornersManager.setRoundedCorners(Window,Object)}.
	 * <p>
	 * On macOS and Wayland, {@code params} is either the corner radius as {@link Float} or an array
	 * of the corner radius ({@link Float}), the border width ({@link Integer}) and the border color
	 * ({@link java.awt.Color}).
	 *
	 * @return {@code true} if the invocation succeeded
	 */
	public static boolean setRoundedCorners( Window window, Object params ) {
		if( !isRoundedCornersSupported() )
			return false;

		try {
			RoundedCorners.SET_ROUNDED_CORNERS.invoke( RoundedCorners.SERVICE, window, params );
			return true;
		} catch( Throwable ex ) {
			return false;
		}
	}

	//---- class loading ------------------------------------------------------

	static Class<?> loadClass( String className ) {
		try {
			return Class.forName( className, false, FlatJBRSupport.class.getClassLoader() );
		} catch( Throwable ex ) {
			// ignore and try context class loader
		}

		try {
			ClassLoader loader = Thread.currentThread().getContextClassLoader();
			if( loader != null )
				return Class.forName( className, false, loader );
		} catch( Throwable ex ) {
			// ignore
		}

		return null;
	}

	/**
	 * Resolves a JetBrains Runtime service, which is available only if {@code JBR.<supportedMethod>()}
	 * answers {@code true}, and the given method of its public interface.
	 *
	 * @return the service and the method; or {@code null} if not available
	 */
	private static Object[] resolveService( String supportedMethod, String getterMethod, String interfaceName,
		String methodName, Class<?>... parameterTypes )
	{
		try {
			Class<?> jbrClass = loadClass( "com.jetbrains.JBR" );
			if( jbrClass == null || !Boolean.TRUE.equals( jbrClass.getMethod( supportedMethod ).invoke( null ) ) )
				return null;

			// Note: getters may be overloaded; passing no parameter types resolves the no-arg method exactly
			Object service = jbrClass.getMethod( getterMethod ).invoke( null );

			// Note: resolving the method on the public and exported interface
			//       because the implementation class is not accessible
			Class<?> serviceInterface = Class.forName( interfaceName, false, jbrClass.getClassLoader() );
			Method method = serviceInterface.getMethod( methodName, parameterTypes );

			return (service != null && serviceInterface.isInstance( service ))
				? new Object[] { service, method }
				: null;
		} catch( Throwable ex ) {
			// JetBrains Runtime API not available, not on the class path, or not compatible
			// (this is the expected case on any other Java runtime)
			return null;
		}
	}

	//---- class WindowMove ---------------------------------------------------

	private static class WindowMove
	{
		static final Object SERVICE;
		static final Method START_MOVING;

		static {
			Object[] resolved = resolveService( "isWindowMoveSupported", "getWindowMove",
				"com.jetbrains.WindowMove", "startMovingTogetherWithMouse", Window.class, int.class );
			SERVICE = (resolved != null) ? resolved[0] : null;
			START_MOVING = (resolved != null) ? (Method) resolved[1] : null;
		}
	}

	//---- class RoundedCorners -----------------------------------------------

	private static class RoundedCorners
	{
		static final Object SERVICE;
		static final Method SET_ROUNDED_CORNERS;

		static {
			Object[] resolved = resolveService( "isRoundedCornersManagerSupported", "getRoundedCornersManager",
				"com.jetbrains.RoundedCornersManager", "setRoundedCorners", Window.class, Object.class );
			SERVICE = (resolved != null) ? resolved[0] : null;
			SET_ROUNDED_CORNERS = (resolved != null) ? (Method) resolved[1] : null;
		}
	}
}
