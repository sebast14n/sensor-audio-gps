package com.example.logger

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.util.MapTileIndex

/**
 * Sursa de tiles satelit (Esri World Imagery), partajata intre MapActivity (afisare)
 * si PoisListActivity (pre-cache offline). Numele "EsriWorldImagery" e ACELASI ca in
 * MapActivity -> cache-ul osmdroid e comun (ce pre-descarcam aici se vede acolo offline).
 */
object SatelliteTiles {
    fun esri(): OnlineTileSourceBase = object : OnlineTileSourceBase(
        "EsriWorldImagery", 0, 19, 256, ".jpg",
        arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
        "Esri World Imagery",
        // NU FLAG_NO_BULK — altfel CacheManager arunca TileSourcePolicyException la pre-cache
        TileSourcePolicy(2, TileSourcePolicy.FLAG_NO_PREVENTIVE
                or TileSourcePolicy.FLAG_USER_AGENT_NORMALIZED
                or TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL)
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val z = MapTileIndex.getZoom(pMapTileIndex)
            val x = MapTileIndex.getX(pMapTileIndex)
            val y = MapTileIndex.getY(pMapTileIndex)
            return "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$y/$x"
        }
    }
}
