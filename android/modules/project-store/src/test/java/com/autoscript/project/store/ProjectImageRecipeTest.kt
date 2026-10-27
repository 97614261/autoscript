package com.autoscript.project.store

import org.junit.Assert.*
import org.junit.Test

class ProjectImageRecipeTest {
    @Test fun strictPortableRoundTripKeepsGeometryWithoutVariablesOrProof() {
        val recipe=ProjectImageRecipe("数字",10,8,listOf(ProjectImageStep("crop",region=ProjectImageRegion(1,2,9,8)),
            ProjectImageStep("rotate90"),ProjectImageStep("resize",width=12,height=16),
            ProjectImageStep("despeckle",cleanup=ProjectImageCleanup(minArea=1))),ProjectImageRegion(0,0,12,16),
            ProjectImageRecipeTail("vision.findimage","""{"template":"assets/images/digit.png","tolerance":0,"similarityPermille":900,"direction":0}"""))
        assertEquals(12 to 16,recipe.dimensions())
        val encoded=encodeImageRecipes(listOf(recipe)).toString(Charsets.UTF_8)
        assertEquals(listOf(recipe),decodeImageRecipes(encoded))
        assertFalse(encoded.contains("Variable"));assertFalse(encoded.contains("proof"))
        assertThrows(Exception::class.java) {decodeImageRecipes(encoded.replace("\"name\":\"数字\"","\"name\":\"数字\",\"name\":\"重复\""))}
        assertThrows(Exception::class.java) {decodeImageRecipes(encoded.replace("\"threshold\":128","\"threshold\":128.0"))}
        assertThrows(Exception::class.java) {decodeImageRecipes(encoded.replace("\"direction\":0","\"direction\":0,\"resultVariable\":\"x\""))}
        assertThrows(Exception::class.java) {encodeImageRecipes(listOf(recipe,recipe))}
        assertThrows(Exception::class.java) {recipe.copy(sourceWidth=8)}
        assertThrows(Exception::class.java) {recipe.validateResources(emptyList())}
        assertThrows(Exception::class.java) {recipe.copy(steps=List(65) {ProjectImageStep("gray")})}
    }
}
