/*
 * Copyright 2026 FormDev Software GmbH
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

import java.awt.Color;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Arrays;
import javax.swing.JRootPane;
import javax.swing.UIManager;
import com.formdev.flatlaf.FlatSystemProperties;
import com.formdev.flatlaf.util.SystemInfo;
import com.formdev.flatlaf.util.UIScale;

/**
 * Rounds the corners of windows that use FlatLaf window decorations on Wayland.
 * <p>
 * Wayland compositors do not decorate such windows, so the corners are rounded
 * (and the border is painted, using {@code RootPane.roundedBorderColor}) by the JetBrains Runtime.
 * Maximized and full screen windows keep square corners.
 *
 * @since 3.8
 */
class FlatWaylandRoundedWindow
	extends WindowAdapter
	implements HierarchyListener
{
	/**
	 * Set on the root pane while the window corners are rounded.
	 * {@link FlatRootPaneUI.FlatWindowBorder} does not paint while it is set.
	 */
	static final String KEY_ROUNDED = "FlatLaf.internal.FlatWaylandRoundedWindow.rounded";

	private static final Object SQUARE = "none";

	private final JRootPane rootPane;
	private final ComponentListener resizeListener = new ComponentAdapter() {
		@Override
		public void componentResized( ComponentEvent e ) {
			update();
		}
	};

	private Window window;
	private Object lastParams;

	static boolean isSupported() {
		return SystemInfo.isWayland() &&
			FlatSystemProperties.getBoolean( FlatSystemProperties.USE_ROUNDED_POPUP_BORDER, true ) &&
			getCornerRadius() > 0 &&
			FlatJBRSupport.isRoundedCornersSupported();
	}

	static FlatWaylandRoundedWindow install( JRootPane rootPane ) {
		FlatWaylandRoundedWindow roundedWindow = new FlatWaylandRoundedWindow( rootPane );
		rootPane.addHierarchyListener( roundedWindow );
		roundedWindow.attach();
		return roundedWindow;
	}

	private FlatWaylandRoundedWindow( JRootPane rootPane ) {
		this.rootPane = rootPane;
	}

	void uninstall() {
		rootPane.removeHierarchyListener( this );
		if( window != null ) {
			if( lastParams != null && lastParams != SQUARE )
				FlatJBRSupport.setRoundedCorners( window, SQUARE );
			detach();
		}
		rootPane.putClientProperty( KEY_ROUNDED, null );
	}

	private void attach() {
		Container parent = rootPane.getParent();
		Window newWindow = (parent instanceof Window) ? (Window) parent : null;
		if( newWindow == window )
			return;

		if( window != null )
			detach();

		window = newWindow;
		if( window != null ) {
			window.addWindowStateListener( this );
			window.addComponentListener( resizeListener );
			update();
		}
	}

	private void detach() {
		window.removeWindowStateListener( this );
		window.removeComponentListener( resizeListener );
		window = null;
		lastParams = null;
	}

	private void update() {
		if( window == null )
			return;

		boolean rounded = !isMaximized() && !FlatUIUtils.isFullScreen( rootPane );
		Object params = rounded ? createParams() : SQUARE;
		if( !paramsEquals( params, lastParams ) ) {
			lastParams = params;
			FlatJBRSupport.setRoundedCorners( window, params );
		}

		if( rounded != (rootPane.getClientProperty( KEY_ROUNDED ) != null) ) {
			rootPane.putClientProperty( KEY_ROUNDED, rounded ? true : null );
			rootPane.repaint();
		}
	}

	private Object createParams() {
		Float radius = UIScale.scale( (float) getCornerRadius() );
		Color borderColor = UIManager.getColor( "RootPane.roundedBorderColor" );
		if( borderColor == null )
			return radius;

		int borderWidth = Math.max( 1, Math.round( UIScale.scale( 1f ) ) );
		return new Object[] { radius, borderWidth, borderColor };
	}

	private boolean isMaximized() {
		return window instanceof Frame &&
			(((Frame) window).getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH;
	}

	private static boolean paramsEquals( Object params1, Object params2 ) {
		if( params1 instanceof Object[] && params2 instanceof Object[] )
			return Arrays.equals( (Object[]) params1, (Object[]) params2 );
		return params1 != null && params1.equals( params2 );
	}

	private static int getCornerRadius() {
		return UIManager.getInt( "RootPane.borderCornerRadius" );
	}

	//---- listeners ----------------------------------------------------------

	@Override
	public void hierarchyChanged( HierarchyEvent e ) {
		if( (e.getChangeFlags() & HierarchyEvent.DISPLAYABILITY_CHANGED) != 0 )
			lastParams = null;
		if( (e.getChangeFlags() & (HierarchyEvent.PARENT_CHANGED | HierarchyEvent.DISPLAYABILITY_CHANGED)) != 0 ) {
			attach();
			update();
		}
	}

	@Override
	public void windowStateChanged( WindowEvent e ) {
		update();
	}
}
