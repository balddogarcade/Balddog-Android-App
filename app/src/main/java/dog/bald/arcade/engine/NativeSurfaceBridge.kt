package dog.bald.arcade.engine

import android.view.Surface

object NativeSurfaceBridge {
    init {
        System.loadLibrary("balddog_android")
    }

    external fun attachSurface(
        surface: Surface,
        width: Int,
        height: Int,
    )

    external fun resizeSurface(
        width: Int,
        height: Int,
    )

    external fun detachSurface()
}
