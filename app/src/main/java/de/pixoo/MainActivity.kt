package de.pixoo

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Collections


class MainActivity : ComponentActivity() {
    private val pixooManager = PixooManager()

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val granted = permissions.entries.all { it.value }
            if (granted) {
                // Permissions granted
            } else {
                // Permissions denied
                throw Exception("Permissions not granted")
            }
        }

    private fun checkAndRequestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissions = arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )

            val missingPermissions = permissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }

            if (missingPermissions.isNotEmpty()) {
                requestPermissionLauncher.launch(missingPermissions.toTypedArray())
            } else {
                // Permissions already granted
            }
        } else {
            // Logic for Android 11 and below; ignore
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        checkAndRequestBluetoothPermissions()
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { PixooApp(pixooManager) } }
    }
}


@Composable
fun PixooApp(pixooManager: PixooManager) {
    var currentMode by remember { mutableStateOf("Connection") }
    var selectedImages by remember { mutableStateOf(listOf<Uri>()) }
    var currentIndex by remember { mutableIntStateOf(0) }
    var fightRound by remember { mutableIntStateOf(1) }
    var currentBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        when (currentMode) {
            "Connection" -> ConnectionScreen(pixooManager) { currentMode = "ImageSettings" }

            "ImageSettings" -> ImageSettingsScreen(
                imageList = selectedImages,
                onAdd = { uri ->
                    selectedImages = selectedImages + uri
                },
                onRemove = { index ->
                    val mutableList = selectedImages.toMutableList()
                    mutableList.removeAt(index)
                    selectedImages = mutableList
                },
                onReorder = { selectedImages = it },
                onPlay = {
                    currentMode = "PlayMode"
                    fightRound = 1
                    currentIndex = 0
                    scope.launch(Dispatchers.IO) {
                        val loadedBitmap = if (selectedImages.isNotEmpty()) {
                            loadBitmapFromUri(
                                context,
                                selectedImages[currentIndex % selectedImages.size]
                            )
                        } else null
                        currentBitmap = loadedBitmap

                        if (loadedBitmap != null) {
                            Log.d("PixooApp", "Sending loaded bitmap to device")
                            pixooManager.sendImage(loadedBitmap, 1)
                        }
                    }
                },
                onOpenSettings = { currentMode = "Connection" },
            )

            "PlayMode" -> PlayModeScreen(
                bitmap = currentBitmap,
                currentRound = fightRound,
                onRoundChange = { newRound ->
                    fightRound = newRound
                    pixooManager.sendImage(currentBitmap, fightRound)
                },
                onNext = {
                    if (selectedImages.isNotEmpty()) {
                        currentIndex = currentIndex + 1
                        if (currentIndex % selectedImages.size == 0) {
                            fightRound++
                        }
                        scope.launch(Dispatchers.IO) {
                            val nextBitmap =
                                loadBitmapFromUri(
                                    context,
                                    selectedImages[currentIndex % selectedImages.size]
                                )
                            currentBitmap = nextBitmap
                            if (nextBitmap != null) {
                                Log.d("PixooApp", "Sending loaded bitmap to device")
                                pixooManager.sendImage(nextBitmap, fightRound)
                            }
                        }
                    } else {
                        Log.e("PixooApp", "No images to display")
                    }
                },
                onStop = { currentMode = "ImageSettings" }
            )
        }

    }
}

@SuppressLint("MissingPermission")
@Composable
fun ConnectionScreen(pixooManager: PixooManager, onConnected: () -> Unit) {
    val adapter = BluetoothAdapter.getDefaultAdapter()
    val devices = adapter?.bondedDevices?.toList() ?: emptyList()

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("1. Connection Settings", style = MaterialTheme.typography.headlineSmall)
        LazyColumn {
            items(devices) { device: BluetoothDevice ->
                Button(
                    onClick = {
                        Thread { if (pixooManager.connect(device)) onConnected() }.start()
                    }, Modifier
                        .fillMaxWidth()
                        .padding(4.dp)
                ) {
                    Text(device.name ?: device.address)
                }
            }
        }
    }
}

@Composable
fun ImageSettingsScreen(
    imageList: List<Uri>,
    onAdd: (Uri) -> Unit,
    onRemove: (Int) -> Unit,
    onReorder: (List<Uri>) -> Unit,
    onPlay: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                onAdd(uri)
            }
        }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, "Settings") }
            Button(onClick = onPlay) { Text("Play Mode") }
        }
        Button(onClick = { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
            Text("Add Image")
        }

        LazyColumn {
            itemsIndexed(imageList) { index, uri ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = uri,
                        contentDescription = "Image $index",
                        modifier = Modifier
                            .size(64.dp)
                            .padding(end = 8.dp)
                            .weight(1f, fill = false),
                        contentScale = ContentScale.Crop
                    )
                    IconButton(onClick = { onReorder(swap(imageList, index, index - 1)) }) {
                        Icon(Icons.Filled.ArrowUpward, "Move Up")
                    }
                    IconButton(onClick = { onReorder(swap(imageList, index, index + 1)) }) {
                        Icon(Icons.Filled.ArrowDownward, "Move Down")
                    }
                    IconButton(onClick = { onRemove(index) }) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Delete Image",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlayModeScreen(
    bitmap: Bitmap?,
    currentRound: Int,
    onRoundChange: (Int) -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Play Mode Active", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            IconButton(onClick = { if (currentRound > 1) onRoundChange(currentRound - 1) }) {
                Icon(Icons.Filled.ArrowDownward, "Decrease Round")
            }

            Text(
                text = "Round: $currentRound",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            IconButton(onClick = {
                onRoundChange(currentRound + 1)
            }) {
                Icon(Icons.Filled.ArrowUpward, "Increase Round")
            }
        }
        Spacer(Modifier.height(24.dp))

        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Scaled Image",
                // Show it bigger than 32x32 for visibility
                modifier = Modifier.size(128.dp),
                contentScale = ContentScale.Fit
            )
        } else {
            Text("No image loaded yet.")
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onNext,
            modifier = Modifier
                .width(200.dp)
                .height(60.dp)
        ) {
            Text(
                "Next Image",
                style = MaterialTheme.typography.titleMedium
            )
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onStop) { Text("Stop & Return") }
    }
}

fun swap(list: List<Uri>, from: Int, to: Int): List<Uri> {
    if (to !in list.indices) return list
    val mutable = list.toMutableList()
    Collections.swap(mutable, from, to)
    return mutable
}

suspend fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
    try {
        val request = ImageRequest.Builder(context)
            .data(uri)
            .size(32, 32)
            .allowHardware(false)
            .bitmapConfig(Bitmap.Config.ARGB_8888)
            .build()
        val result = context.imageLoader.execute(request)
        if (result is SuccessResult) {
            return (result.drawable as? BitmapDrawable)?.bitmap
        }
    } catch (e: Exception) {
        Log.e("PixooSender", "Load failed: ${e.message}")
    }
    return null
}
