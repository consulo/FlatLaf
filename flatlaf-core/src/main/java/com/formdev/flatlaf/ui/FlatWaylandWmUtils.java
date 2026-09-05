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

import static java.awt.Cursor.*;
import java.awt.Component;
import java.awt.Frame;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.MouseEvent;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import javax.swing.SwingUtilities;
import com.formdev.flatlaf.FlatSystemProperties;
import com.formdev.flatlaf.util.LoggingFacade;
import com.formdev.flatlaf.util.SystemInfo;

/**
 * Window manager utilities for the Wayland AWT toolkit ({@code sun.awt.wl.WLToolkit}).
 * <p>
 * On Wayland, an application can not change the location of its own windows.
 * {@link Window#setLocation(int, int)} is a no-op and {@link Window#getX()}/{@link Window#getY()}
 * do not report the real window location
 * (see <a href="https://youtrack.jetbrains.com/issue/JBR-10322">JBR-10322</a>).
 * A window can only be moved by asking the compositor to take over an ongoing mouse
 * drag (Wayland request {@code xdg_toplevel.move}). The JetBrains Runtime exposes this
 * as {@code com.jetbrains.WindowMove.startMovingTogetherWithMouse(Window,int)}.
 * <p>
 * This class answers a single capability question: <em>can FlatLaf decorate and move
 * windows in this environment?</em> (see {@link #isSupported()}). If it answers
 * {@code false}, then FlatLaf window decorations stay disabled on Wayland and the
 * compositor decorations are used (as in FlatLaf 3.7).
 * <p>
 * The JetBrains Runtime API is used via reflection only. FlatLaf has no compile-time
 * dependency on {@code jbr-api}. The reflective lookup is done at most once per JVM
 * (in the class initializer of the nested holder class) and never per mouse event.
 * <p>
 * <b>Note</b>: This is private API. Do not use!
 *
 * @since 3.8
 */
public class FlatWaylandWmUtils
{
	/**
	 * Specifies whether FlatLaf window decorations may be used when the Wayland AWT
	 * toolkit is active and the JetBrains Runtime window move API is available.
	 * <p>
	 * Setting this to {@code false} keeps FlatLaf window decorations disabled on Wayland,
	 * which means that the compositor decorations are used.
	 * <p>
	 * <strong>Allowed Values</strong> {@code false} and {@code true}<br>
	 * <strong>Default</strong> {@code true}
	 */
	public static final String USE_WAYLAND_WINDOW_DECORATIONS = "flatlaf.useWaylandWindowDecorations";

	// set to true after the first failed invocation to avoid flooding the log while dragging
	private static boolean loggedMoveFailure;

	// set to true after the first failed invocation to avoid flooding the log
	private static boolean loggedMenuFailure;

	private FlatWaylandWmUtils() {}

	/**
	 * Computes {@link #isSupported()} in the background, if running on Wayland.
	 * <p>
	 * Resolving the JetBrains Runtime API bootstraps the JBR service registry, which
	 * takes a noticeable amount of time. Invoke this when installing the look and feel
	 * so that the (much later) first invocation of
	 * {@link javax.swing.LookAndFeel#getSupportsWindowDecorations()} from
	 * {@code JFrame.frameInit()} does not pay that cost on the event dispatch thread.
	 * <p>
	 * This is an optimization only. Not invoking it changes nothing but timing.
	 */
	public static void preload() {
		try {
			// invoked on the caller thread so that the AWT toolkit is initialized here
			// and not on the background thread
			if( !SystemInfo.isWayland() )
				return;

			Thread thread = new Thread( FlatWaylandWmUtils::isSupported, "FlatLaf Wayland window move probe" );
			thread.setDaemon( true );
			thread.setPriority( Thread.MIN_PRIORITY );
			thread.start();
		} catch( Throwable ex ) {
			// ignore; this is an optimization only and must never break look and feel installation
		}
	}

	/**
	 * Checks whether FlatLaf window decorations can be used with the Wayland AWT toolkit.
	 * <p>
	 * Returns {@code true} only if the Wayland AWT toolkit is used <b>and</b> the
	 * JetBrains Runtime window move API is available and usable. Returns {@code false}
	 * on all other platforms and toolkits (Windows, macOS, Linux/X11, headless)
	 * without loading any JetBrains Runtime class.
	 * <p>
	 * The result is computed once and never changes for the lifetime of the JVM.
	 */
	public static boolean isSupported() {
		// check Wayland first so that the JetBrains Runtime API is never loaded
		// on Windows, macOS or on Linux with X Window System
		return SystemInfo.isWayland() && JBRWindowMove.START_MOVING != null;
	}

	/**
	 * Checks whether the given window can be moved by the Wayland compositor.
	 * <p>
	 * Popup windows are excluded because the compositor moves only top-level windows.
	 * The Wayland peer of the JetBrains Runtime ignores them too.
	 */
	static boolean canMoveWindow( Window window ) {
		return window != null &&
			window.getType() != Window.Type.POPUP &&
			isSupported();
	}

	/**
	 * Asks the Wayland compositor to move the given window together with the mouse
	 * until the pressed mouse button is released.
	 * <p>
	 * If the window is maximized, it is un-maximized first, because a compositor may
	 * ignore an interactive move request for a maximized window.
	 * <p>
	 * If this returns {@code true}, then the compositor owns the drag gesture and the
	 * caller must stop moving the window itself. The application usually does not
	 * receive further mouse events of that gesture, not even a mouse released event.
	 * <p>
	 * <b>Note</b>: The JetBrains Runtime API does not report whether the compositor
	 *              really started the move. If it did not (e.g. because the Wayland
	 *              input serial of the drag is stale), then this returns {@code true}
	 *              although the window does not move.
	 */
	static boolean moveWindow( Window window ) {
		if( !canMoveWindow( window ) )
			return false;

		// Wayland can not move a maximized window; unset maximized state first so that
		// the compositor sees a regular top-level window when the move request arrives
		if( window instanceof Frame ) {
			Frame frame = (Frame) window;
			int state = frame.getExtendedState();
			if( (state & Frame.MAXIMIZED_BOTH) != 0 )
				frame.setExtendedState( state & ~Frame.MAXIMIZED_BOTH );
		}

		try {
			// the mouse button is passed for API compatibility only;
			// the Wayland implementation uses the current pointer input serial instead
			JBRWindowMove.START_MOVING.invoke( JBRWindowMove.WINDOW_MOVE, window, MouseEvent.BUTTON1 );
			return true;
		} catch( Throwable ex ) {
			if( !loggedMoveFailure ) {
				loggedMoveFailure = true;
				LoggingFacade.INSTANCE.logSevere( "FlatLaf: failed to move window using JetBrains Runtime API",
					(ex instanceof InvocationTargetException) ? ex.getCause() : ex );
			}
			return false;
		}
	}

	/**
	 * Adjusts a resize direction (a {@code *_RESIZE_CURSOR} constant of
	 * {@link java.awt.Cursor}) for the Wayland AWT toolkit.
	 * <p>
	 * Returns the given resize direction unchanged if not running on Wayland.
	 * <p>
	 * On Wayland, resizing at the top or left window edge would have to change the
	 * window location, which is not possible (see class javadoc), and the JetBrains
	 * Runtime does not expose a compositor-driven resize API. Resizing is therefore
	 * limited to the bottom edge, the right edge and the bottom-right corner, which
	 * change only the window size. {@link java.awt.Cursor#DEFAULT_CURSOR} is returned
	 * for all other directions, which disables resizing there.
	 * <p>
	 * Note that this does not depend on {@link #isSupported()}: the restriction is a
	 * property of Wayland, not of the JetBrains Runtime window move API.
	 */
	static int adjustResizeDir( int resizeDir ) {
		if( !SystemInfo.isWayland() )
			return resizeDir;

		switch( resizeDir ) {
			// bottom edge, right edge and bottom-right corner change only window size
			case S_RESIZE_CURSOR:
			case E_RESIZE_CURSOR:
			case SE_RESIZE_CURSOR:
				return resizeDir;

			// all other directions would change the window location --> not resizable
			default:
				return DEFAULT_CURSOR;
		}
	}

	//---- class JBRWindowMove ------------------------------------------------

	/**
	 * Lazy holder for reflective access to {@code com.jetbrains.WindowMove}.
	 * <p>
	 * Initialized on first access to one of its fields, which happens in
	 * {@link FlatWaylandWmUtils#isSupported()} and only if running on Wayland.
	 * The JVM runs the class initializer exactly once, thread-safe, and safely
	 * publishes both fields. Both fields are {@code null} if the API is not available.
	 */
	/**
	 * Checks whether the window menu of the Wayland compositor can be shown for the given window.
	 * &lt;p&gt;
	 * Requires that package {@code sun.awt.wl} of module {@code java.desktop} is open,
	 * which the application must request with a VM option, e.g.
	 * {@code --add-opens java.desktop/sun.awt.wl=ALL-UNNAMED}.
	 *
	 * @since 3.8
	 */
	static boolean canShowWindowMenu( Window window ) {
		return window != null &&
			window.getType() != Window.Type.POPUP &&
			SystemInfo.isWayland() &&
			WLWindowMenu.SHOW_WINDOW_MENU != null;
	}

	/**
	 * Shows the window menu provided by the Wayland compositor
	 * (Wayland request {@code xdg_toplevel.show_window_menu}).
	 * &lt;p&gt;
	 * The Wayland protocol requires that this is done in response to a user action,
	 * identified by the serial of the input event that requested the menu. That serial
	 * is tracked by the Wayland AWT toolkit and is not part of the public API,
	 * so it is obtained reflectively.
	 *
	 * @return {@code true} if the request was sent to the compositor
	 * @since 3.8
	 */
	static boolean showWindowMenu( Window window, MouseEvent e ) {
		if( !canShowWindowMenu( window ) )
			return false;

		try {
			Object peer = WLWindowMenu.PEER.get( window );
			if( peer == null )
				return false;

			Object inputState = WLWindowMenu.GET_INPUT_STATE.invoke( null );
			Object inputSerial = WLWindowMenu.POINTER_BUTTON_SERIAL.invoke( inputState );
			long serial = (Long) WLWindowMenu.SERIAL.invoke( inputSerial );
			if( serial == 0 )
				return false; // no user action to attach the request to

			// coordinates are relative to the window, in Java units
			Point pt = SwingUtilities.convertPoint( e.getComponent(), e.getPoint(), window );
			WLWindowMenu.SHOW_WINDOW_MENU.invoke( peer, serial, pt.x, pt.y );
			return true;
		} catch( Throwable ex ) {
			if( !loggedMenuFailure ) {
				loggedMenuFailure = true;
				LoggingFacade.INSTANCE.logSevere( "FlatLaf: failed to show Wayland window menu",
					(ex instanceof InvocationTargetException) ? ex.getCause() : ex );
			}
			return false;
		}
	}

	/**
	 * Reflective access to the window menu support of the Wayland AWT toolkit.
	 * Resolved at most once per JVM.
	 */
	private static class WLWindowMenu
	{
		/** {@code sun.awt.wl.WLToolkit.getInputState()}; or {@code null} if not available */
		static final Method GET_INPUT_STATE;
		/** {@code sun.awt.wl.WLInputState.pointerButtonSerial()}; or {@code null} */
		static final Method POINTER_BUTTON_SERIAL;
		/** {@code sun.awt.wl.WLInputSerial.serial()}; or {@code null} */
		static final Method SERIAL;
		/** {@code sun.awt.wl.WLComponentPeer.showWindowMenu(long,int,int)}; or {@code null} */
		static final Method SHOW_WINDOW_MENU;
		/** {@code java.awt.Component.peer}; or {@code null} */
		static final Field PEER;

		static {
			Method getInputState = null;
			Method pointerButtonSerial = null;
			Method serial = null;
			Method showWindowMenu = null;
			Field peer = null;

			try {
				ClassLoader loader = Toolkit.getDefaultToolkit().getClass().getClassLoader();

				Class<?> toolkitClass = Class.forName( "sun.awt.wl.WLToolkit", false, loader );
				getInputState = toolkitClass.getDeclaredMethod( "getInputState" );
				getInputState.setAccessible( true );

				// resolve the record accessors from an actual instance because the
				// record classes are not public
				Object inputState = getInputState.invoke( null );
				pointerButtonSerial = inputState.getClass().getDeclaredMethod( "pointerButtonSerial" );
				pointerButtonSerial.setAccessible( true );

				Object inputSerial = pointerButtonSerial.invoke( inputState );
				serial = inputSerial.getClass().getDeclaredMethod( "serial" );
				serial.setAccessible( true );

				Class<?> peerClass = Class.forName( "sun.awt.wl.WLComponentPeer", false, loader );
				showWindowMenu = peerClass.getDeclaredMethod( "showWindowMenu", long.class, int.class, int.class );
				showWindowMenu.setAccessible( true );

				peer = Component.class.getDeclaredField( "peer" );
				peer.setAccessible( true );
			} catch( Throwable ex ) {
				// package sun.awt.wl (or java.awt) not open, or not running on the Wayland toolkit
				// --> silently fall back to no window menu (same as FlatLaf 3.7)
				getInputState = null;
				pointerButtonSerial = null;
				serial = null;
				showWindowMenu = null;
				peer = null;
			}

			GET_INPUT_STATE = getInputState;
			POINTER_BUTTON_SERIAL = pointerButtonSerial;
			SERIAL = serial;
			SHOW_WINDOW_MENU = showWindowMenu;
			PEER = peer;
		}
	}

	private static class JBRWindowMove
	{
		/** instance of {@code com.jetbrains.WindowMove}; or {@code null} if not available */
		static final Object WINDOW_MOVE;
		/** {@code com.jetbrains.WindowMove.startMovingTogetherWithMouse(Window,int)}; or {@code null} */
		static final Method START_MOVING;

		static {
			Object windowMove = null;
			Method startMoving = null;

			try {
				if( FlatSystemProperties.getBoolean( USE_WAYLAND_WINDOW_DECORATIONS, true ) ) {
					Class<?> jbrClass = loadClass( "com.jetbrains.JBR" );
					if( jbrClass != null &&
						Boolean.TRUE.equals( jbrClass.getMethod( "isWindowMoveSupported" ).invoke( null ) ) )
					{
						// Note: getWindowMove() is overloaded; passing no parameter types
						//       resolves the no-arg method exactly
						Object service = jbrClass.getMethod( "getWindowMove" ).invoke( null );

						// Note: resolving the method on the public and exported interface
						//       because the implementation class is not accessible
						Class<?> windowMoveClass = Class.forName( "com.jetbrains.WindowMove", false, jbrClass.getClassLoader() );
						Method method = windowMoveClass.getMethod( "startMovingTogetherWithMouse", Window.class, int.class );

						if( service != null && windowMoveClass.isInstance( service ) ) {
							windowMove = service;
							startMoving = method;
						}
					}
				}
			} catch( Throwable ex ) {
				// JetBrains Runtime API not available, not on the class path, or not compatible
				// --> silently keep FlatLaf window decorations disabled on Wayland
				// (this is the expected case on any other Java runtime)
			}

			WINDOW_MOVE = windowMove;
			START_MOVING = startMoving;
		}

		private static Class<?> loadClass( String className ) {
			try {
				return Class.forName( className, false, FlatWaylandWmUtils.class.getClassLoader() );
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
	}
}
