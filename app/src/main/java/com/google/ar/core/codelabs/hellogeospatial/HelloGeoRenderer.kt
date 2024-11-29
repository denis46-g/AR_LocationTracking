/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ar.core.codelabs.hellogeospatial

import android.graphics.Color
import android.opengl.Matrix
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.maps.model.LatLng
import com.google.ar.core.Anchor
import com.google.ar.core.GeospatialPose
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper
import com.google.ar.core.examples.java.common.helpers.TrackingStateHelper
import com.google.ar.core.examples.java.common.samplerender.Framebuffer
import com.google.ar.core.examples.java.common.samplerender.Mesh
import com.google.ar.core.examples.java.common.samplerender.SampleRender
import com.google.ar.core.examples.java.common.samplerender.Shader
import com.google.ar.core.examples.java.common.samplerender.Texture
import com.google.ar.core.examples.java.common.samplerender.arcore.BackgroundRenderer
import com.google.ar.core.exceptions.CameraNotAvailableException
import java.io.IOException
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.times

import androidx.lifecycle.lifecycleScope
import com.google.ar.core.Earth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.min

var AnchorsDatabaseList: MutableList<com.google.ar.core.codelabs.hellogeospatial.data.Anchor>? = mutableListOf()
var doAction = 0
val eps = 0.001

class HelloGeoRenderer(val activity: HelloGeoActivity) :
  SampleRender.Renderer, DefaultLifecycleObserver {
  //<editor-fold desc="ARCore initialization" defaultstate="collapsed">
  companion object {
    val TAG = "HelloGeoRenderer"

    private val Z_NEAR = 0.1f
    private val Z_FAR = 1000f
  }

  lateinit var backgroundRenderer: BackgroundRenderer
  lateinit var virtualSceneFramebuffer: Framebuffer
  var hasSetTextureNames = false

  // Virtual object (ARCore pawn)
  lateinit var virtualObjectMesh: Mesh
  lateinit var virtualObjectShader: Shader
  lateinit var virtualObjectTexture: Texture

  // Temporary matrix allocated here to reduce number of allocations for each frame.
  val modelMatrix = FloatArray(16)
  val viewMatrix = FloatArray(16)
  val projectionMatrix = FloatArray(16)
  val modelViewMatrix = FloatArray(16) // view x model

  val modelViewProjectionMatrix = FloatArray(16) // projection x view x model

  val session
    get() = activity.arCoreSessionHelper.session

  val displayRotationHelper = DisplayRotationHelper(activity)
  val trackingStateHelper = TrackingStateHelper(activity)

  override fun onResume(owner: LifecycleOwner) {
    displayRotationHelper.onResume()
    hasSetTextureNames = false
  }

  override fun onPause(owner: LifecycleOwner) {
    displayRotationHelper.onPause()
  }

  override fun onSurfaceCreated(render: SampleRender) {
    // Prepare the rendering objects.
    // This involves reading shaders and 3D model files, so may throw an IOException.
    try {
      backgroundRenderer = BackgroundRenderer(render)
      virtualSceneFramebuffer = Framebuffer(render, /*width=*/ 1, /*height=*/ 1)

      // Virtual object to render (Geospatial Marker)
      virtualObjectTexture =
        Texture.createFromAsset(
          render,
          "models/spatial_marker_baked.png",
          Texture.WrapMode.CLAMP_TO_EDGE,
          Texture.ColorFormat.SRGB
        )

      virtualObjectMesh = Mesh.createFromAsset(render, "models/geospatial_marker.obj");
      virtualObjectShader =
        Shader.createFromAssets(
          render,
          "shaders/ar_unlit_object.vert",
          "shaders/ar_unlit_object.frag",
          /*defines=*/ null)
          .setTexture("u_Texture", virtualObjectTexture)

      backgroundRenderer.setUseDepthVisualization(render, false)
      backgroundRenderer.setUseOcclusion(render, false)
    } catch (e: IOException) {
      Log.e(TAG, "Failed to read a required asset file", e)
      showError("Failed to read a required asset file: $e")
    }
  }

  override fun onSurfaceChanged(render: SampleRender, width: Int, height: Int) {
    displayRotationHelper.onSurfaceChanged(width, height)
    virtualSceneFramebuffer.resize(width, height)
  }
  //</editor-fold>

  override fun onDrawFrame(render: SampleRender) {
    val session = session ?: return

    val button = activity.view.root.findViewById<Button>(R.id.button)
    val buttonAction = activity.view.root.findViewById<Button>(R.id.buttonAction)
    val helpTextView = activity.view.root.findViewById<TextView>(R.id.helpTextView)

    //<editor-fold desc="ARCore frame boilerplate" defaultstate="collapsed">
    // Texture names should only be set once on a GL thread unless they change. This is done during
    // onDrawFrame rather than onSurfaceCreated since the session is not guaranteed to have been
    // initialized during the execution of onSurfaceCreated.
    if (!hasSetTextureNames) {
      session.setCameraTextureNames(intArrayOf(backgroundRenderer.cameraColorTexture.textureId))
      hasSetTextureNames = true
    }

    // -- Update per-frame state

    // Notify ARCore session that the view size changed so that the perspective matrix and
    // the video background can be properly adjusted.
    displayRotationHelper.updateSessionIfNeeded(session)

    // Obtain the current frame from ARSession. When the configuration is set to
    // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
    // camera framerate.
    val frame =
      try {
        session.update()
      } catch (e: CameraNotAvailableException) {
        Log.e(TAG, "Camera not available during onDrawFrame", e)
        showError("Camera not available. Try restarting the app.")
        return
      }

    val camera = frame.camera

    // BackgroundRenderer.updateDisplayGeometry must be called every frame to update the coordinates
    // used to draw the background camera image.
    backgroundRenderer.updateDisplayGeometry(frame)

    // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
    trackingStateHelper.updateKeepScreenOnFlag(camera.trackingState)

    // -- Draw background
    if (frame.timestamp != 0L) {
      // Suppress rendering if the camera did not produce the first frame yet. This is to avoid
      // drawing possible leftover data from previous sessions if the texture is reused.
      backgroundRenderer.drawBackground(render)
    }

    // If not tracking, don't draw 3D objects.
    if (camera.trackingState == TrackingState.PAUSED) {
      return
    }

    // Get projection matrix.
    camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR)

    // Get camera matrix and draw.
    camera.getViewMatrix(viewMatrix, 0)

    render.clear(virtualSceneFramebuffer, 0f, 0f, 0f, 0f)
    //</editor-fold>

    val earth = session.earth
    if (earth?.trackingState == TrackingState.TRACKING) {
      val cameraGeospatialPose = earth.cameraGeospatialPose
      activity.view.mapView?.updateMapPosition(
        latitude = cameraGeospatialPose.latitude,
        longitude = cameraGeospatialPose.longitude,
        heading = cameraGeospatialPose.heading
      )
    }
    if (earth != null) {
      activity.view.updateStatusText(earth, earth.cameraGeospatialPose)
    }

    if(Anchors == null && AnchorsCoordinates == null ||
      Anchors?.size == 0 && AnchorsCoordinates?.size == 0){
      activity.initListAnchor()
      Anchors = AnchorFromDBtoRealAnchor()
      AnchorsCoordinates = GetAnchorsCoordinatesFromDB()
    }

    if(activity.view.mapView?.earthMarkers == null ||
      activity.view.mapView?.earthMarkers!!.size == 0 && AnchorsCoordinates != null) {
      try{
        InitMarkers()
      }
      catch (e: Exception){
        Log.d("MyMarkersException", "Error after attempt to draw markers")
      }
    }

    buttonAction.setOnClickListener{
      if(doAction == 0)
        doAction = 1
      else
        doAction = 0
    }

    button.setOnClickListener {

      if((activity.view.mapView?.earthMarkers == null ||
                activity.view.mapView?.earthMarkers!!.size == 0) && AnchorsCoordinates != null) {
        try {
          InitMarkers()
        }
        catch (e: Exception){
          Log.d("MyMarkersException", "Error after attempt to draw markers")
        }
      }

      if (Anchors != null && Anchors!!.size >= 3) {
        Anchors!!.removeAt(0)
        AnchorsCoordinates?.removeAt(0)
        activity.deleteFirstAnchor()
        activity.view.mapView?.earthMarkers?.first()?.apply {
          isVisible = false
        }
        activity.view.mapView?.earthMarkers?.removeAt(0)
      }

      //для рендеринга
      if (earth != null) {
        Anchors?.add(
          earth.createAnchor(
            earth.cameraGeospatialPose.latitude,
            earth.cameraGeospatialPose.longitude,
            earth.cameraGeospatialPose.altitude - 1.3, 0f, 0f, 0f, 1f
          )
        )
      }

      if (earth != null) {
        AnchorsCoordinates?.add(
          Pair(
            earth.cameraGeospatialPose.latitude,
            earth.cameraGeospatialPose.longitude
          )
        )
      }

      //для бд
      val newAnchor = earth?.cameraGeospatialPose?.let { it1 ->
        com.google.ar.core.codelabs.hellogeospatial.data.Anchor(
          latitude = earth?.cameraGeospatialPose!!.latitude,
          longitude = it1.longitude
        )
      }
      if (newAnchor != null) {
        activity.insertAnchor(newAnchor)
      }

      activity.view.mapView?.addMarker(Color.argb(255, 125, 125, 125))

      activity.view.mapView?.earthMarkers?.last()?.apply {
        if (earth != null) {
          position = LatLng(earth.cameraGeospatialPose.latitude, earth.cameraGeospatialPose.longitude)
        }
        isVisible = true
      }
    }

    val minDistance = GetMinDistance(earth)

    // Draw the placed anchor, if it exists.
    Anchors?.let {
      for(anchor in it){
        if (earth != null) {
          val ind = Anchors!!.indexOf(anchor)
          userAnchorDistance = AnchorsCoordinates?.get(ind)?.let { it1 ->
            haversineDistance(
              it1.first, it1.second,
              earth.cameraGeospatialPose.latitude, earth.cameraGeospatialPose.longitude)
          }
        }
        //if(userAnchorDistance!! <= 10){
          if(abs(userAnchorDistance!! - minDistance) < eps){
            activity.runOnUiThread{
              if(isAnchorVisible(anchor, earth) && activity.textViewIsTouched){
                buttonAction.visibility = View.VISIBLE
                helpTextView.visibility = View.INVISIBLE
              }
              else if(activity.textViewIsTouched){
                buttonAction.visibility = View.INVISIBLE
                helpTextView.visibility = View.VISIBLE
              }
            }
            render.renderCompassAtAnchor(anchor, doAction)
          }
          else
            render.renderCompassAtAnchor(anchor)
        //}
      }
    }

    // Compose the virtual scene with the background.
    backgroundRenderer.drawVirtualScene(render, virtualSceneFramebuffer, Z_NEAR, Z_FAR)
  }

  var Anchors: MutableList<Anchor>? = mutableListOf()
  var AnchorsCoordinates: MutableList<Pair<Double, Double>>? = mutableListOf()
  var userAnchorDistance: Double? = null

  fun InitMarkers(){
    for(anchor in AnchorsCoordinates!!){
      activity.view.mapView?.addMarker(Color.argb(255, 125, 125, 125))
      activity.view.mapView?.earthMarkers?.last()?.apply {
        position = LatLng(anchor.first, anchor.second)
        isVisible = true
      }
    }
  }

  fun AnchorFromDBtoRealAnchor(): MutableList<Anchor>? {
    var listAnchors: MutableList<Anchor> = mutableListOf()
    val earth = session?.earth
    val altitude = (earth?.cameraGeospatialPose?.altitude ?: 0.0) - 1.3
    if(AnchorsDatabaseList != null){
      for(anchor in AnchorsDatabaseList!!){
        if (earth != null) {
          listAnchors.add(earth.createAnchor(anchor.latitude, anchor.longitude,
            altitude, 0f, 0f, 0f, 1f))
        }
      }
    }
    return listAnchors
  }

  fun GetAnchorsCoordinatesFromDB(): MutableList<Pair<Double, Double>>? {
    var listAnhorsCoordinates: MutableList<Pair<Double, Double>> = mutableListOf()
    val earth = session?.earth
    if(AnchorsDatabaseList != null){
      for(anchor in AnchorsDatabaseList!!){
        if (earth != null) {
          listAnhorsCoordinates.add(Pair(anchor.latitude, anchor.longitude))
        }
      }
    }
    return listAnhorsCoordinates
  }

  fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6371000 // радиус Земли в метрах
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)

    val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)

    val c = 2 * atan2(sqrt(a), sqrt(1 - a))

    return R * c // расстояние в метрах
  }

  fun GetMinDistance(earth: Earth?): Double {
    var res = Double.MAX_VALUE
    Anchors?.let {
      for (anchor in it) {
        if (earth != null) {
          val ind = Anchors!!.indexOf(anchor)
          userAnchorDistance = AnchorsCoordinates?.get(ind)?.let { it1 ->
            haversineDistance(
              it1.first, it1.second,
              earth.cameraGeospatialPose.latitude, earth.cameraGeospatialPose.longitude
            )
          }
          if(userAnchorDistance!! < res)
            res = userAnchorDistance as Double
        }
      }
    }
    return res
  }

  private fun isAnchorVisible(anchor: Anchor, earth: Earth?): Boolean {
    // Получаем позу якоря
    val anchorPose = anchor.pose

    // Получаем кватернион якоря
    val anchorQuaternion = anchorPose.rotationQuaternion // [x, y, z, w]

    // Извлекаем компоненты кватерниона
    val (x, y, z, w) = anchorQuaternion

    // Вычисляем матрицу вращения из кватерниона
    val heading = atan2(2.0 * (y * w + x * z), (w * w + x * x - y * y - z * z).toDouble())

    // Переводим радианы в градусы
    val headingDegrees = Math.toDegrees(heading)

    // Получаем позу камеры
    val cameraPose = earth?.cameraGeospatialPose ?: return false

    // Получаем направление heading камеры (угол в градусах)
    val cameraHeading = cameraPose.heading // Угол ориентации камеры

    return abs((headingDegrees + 360) % 360 - cameraHeading) <= 75
    //return abs(headingDegrees - cameraHeading) < 80

    /*// Положение камеры
    val cameraPosition = FloatArray(3).apply {
      this[0] = cameraPose.longitude.toFloat() // Долгота
      this[1] = cameraPose.latitude.toFloat()   // Широта
      this[2] = cameraPose.altitude.toFloat()    // Высота
    }

    // Рассчитываем вектор от камеры до якоря
    val directionToAnchor = FloatArray(3).apply {
      this[0] = anchorPosition[0] - cameraPosition[0]
      this[1] = anchorPosition[1] - cameraPosition[1]
      this[2] = anchorPosition[2] - cameraPosition[2]
    }

    // Нормализуем этот вектор
    val normalizedDirectionToAnchor = normalize(directionToAnchor)

    // Поскольку у нас нет ориентации камеры, предполагаем, что камера всегда направлена вниз. Это требует дальнейших уточнений в вашем коде.
    val cameraForward = floatArrayOf(0f, 0f, -1f)

    // Нормализуем направление камеры
    val normalizedCameraForward = normalize(cameraForward)

    // Вычисляем угол между векторами
    val angle = cos(dotProduct(normalizedCameraForward, normalizedDirectionToAnchor))

    // Определяем горизонтальное поле зрения
    val horizontalFOV = Math.toRadians(60.0)  // горизонтальное поле зрения (пример)

    // Проверяем, попадает ли угол в поле зрения
    // Если угол меньше половины FOV, объект считается видимым
    return angle <= (horizontalFOV / 2)*/


  }

  // Нормализация вектора
  private fun normalize(vector: FloatArray): FloatArray {
    val length = sqrt(vector[0] * vector[0] + vector[1] * vector[1] + vector[2] * vector[2])
    return if (length > 0.0) {
      FloatArray(3).apply {
        this[0] = vector[0] / length
        this[1] = vector[1] / length
        this[2] = vector[2] / length
      }
    } else {
      vector
    }
  }

  // Скалярное произведение
  private fun dotProduct(a: FloatArray, b: FloatArray): Double {
    return (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).toDouble()
  }



  fun onMapClick(latLng: LatLng) {
    val earth = session?.earth ?: return
    if (earth.trackingState != TrackingState.TRACKING) {
      return
    }

    //earthAnchor?.detach()

    //val button = activity.view.root.findViewById<Button>(R.id.button)

    if((activity.view.mapView?.earthMarkers == null ||
      activity.view.mapView?.earthMarkers!!.size == 0) && AnchorsCoordinates != null) {
      for (anchor in AnchorsCoordinates!!) {
        activity.view.mapView?.addMarker(Color.argb(255, 125, 125, 125))
        activity.view.mapView?.earthMarkers?.last()?.apply {
          position = LatLng(anchor.first, anchor.second)
          isVisible = true
        }
      }
    }

    if (Anchors != null && Anchors!!.size >= 3) {
      Anchors!!.removeAt(0)
      AnchorsCoordinates?.removeAt(0)
      activity.deleteFirstAnchor()
      activity.view.mapView?.earthMarkers?.first()?.apply {
        isVisible = false
      }
      activity.view.mapView?.earthMarkers?.removeAt(0)
    }
    // Place the earth anchor at the same altitude as that of the camera to make it easier to view.
    val altitude = earth.cameraGeospatialPose.altitude - 1.3
    // The rotation quaternion of the anchor in the East-Up-South (EUS) coordinate system.
    val qx = 0f
    val qy = 0f
    val qz = 0f
    val qw = 1f
    Anchors?.add(
      earth.createAnchor(latLng.latitude, latLng.longitude, altitude, qx, qy, qz, qw))

    AnchorsCoordinates?.add(
      Pair(
        latLng.latitude,
        latLng.longitude
      )
    )

    //для бд
    val newAnchor = com.google.ar.core.codelabs.hellogeospatial.data.Anchor(
      latitude = latLng.latitude,
      longitude = latLng.longitude
    )
    activity.insertAnchor(newAnchor)

    activity.view.mapView?.addMarker(Color.argb(255, 125, 125, 125))

    activity.view.mapView?.earthMarkers?.last()?.apply {
      position = latLng
      isVisible = true
    }
  }

  private fun SampleRender.renderCompassAtAnchor(anchor: Anchor, action: Int = 0) {
    // Get the current pose of the Anchor in world space. The Anchor pose is updated
    // during calls to session.update() as ARCore refines its estimate of the world.
    anchor.pose.toMatrix(modelMatrix, 0)

    // Calculate model/view/projection matrices
    Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0)
    Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)

    // Update shader properties and draw
    virtualObjectShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix)

    // Передаем цвет в шейдер
    virtualObjectShader.setInt("action", action)

    draw(virtualObjectMesh, virtualObjectShader, virtualSceneFramebuffer)
  }

  private fun showError(errorMessage: String) =
    activity.view.snackbarHelper.showError(activity, errorMessage)
}
