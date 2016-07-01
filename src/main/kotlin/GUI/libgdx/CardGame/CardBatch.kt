package GUI.libgdx.CardGame

import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.g3d.Material
import com.badlogic.gdx.graphics.g3d.Renderable
import com.badlogic.gdx.graphics.g3d.RenderableProvider
import com.badlogic.gdx.graphics.g3d.utils.MeshBuilder
import com.badlogic.gdx.utils.Array
import com.badlogic.gdx.utils.Disposable
import com.badlogic.gdx.utils.ObjectSet
import com.badlogic.gdx.utils.Pool


/**
 * Created by user on 7/1/16.
 */


class CardBatch(material: Material): ObjectSet<Card>(), RenderableProvider, Disposable{
    val renderable = Renderable()
    val mesh: Mesh
    val meshBuilder = MeshBuilder()

    init{
        val maxNumberOfCards = 52
        val maxNumberOfVertices = maxNumberOfCards * 8
        val maxNumberOfIndices = maxNumberOfCards * 12
        mesh = Mesh(false, maxNumberOfVertices, maxNumberOfIndices,
                VertexAttribute.Position(), VertexAttribute.Normal(), VertexAttribute.TexCoords(0))

        renderable.material = material
    }
    override fun getRenderables(renderables: Array<Renderable>, pool: Pool<Renderable>?) {
        meshBuilder.begin(mesh.vertexAttributes)
        meshBuilder.part("cards", GL20.GL_TRIANGLES, renderable.meshPart)
        for(card in this){
            meshBuilder.setVertexTransform(card.transform)
            meshBuilder.addMesh(card.verticies, card.indices)
        }
        meshBuilder.end(mesh)
        renderables.add(renderable)
    }

    override fun dispose() {
        mesh.dispose()
    }
}
