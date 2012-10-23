#include "ImageStack.h"
#include "UTF8Model.h"
#include "json/reader.h"
#include "JsonUtils.h"
#include "Assets/Shader/ShaderProgram.h"
#include "Common/GL.h"
#include "Math/TransformStack.h"
#include "Picker.h"
#include <fstream>
#include <codecvt>

using namespace std;
using namespace GraphicsEngine;
using Eigen::Vector3f;
using Eigen::Vector2f;
using Eigen::AlignedBox3f;

void ParseMaterials(UTF8Model* model, const Json::Value& root, const string& texDir)
{
	const Json::Value mats = JsonUtils::GetRequiredJsonProp(root, "materials");
	const Json::Value::Members matnames = mats.getMemberNames();
	for (UINT i = 0; i < matnames.size(); i++)
	{
		const string& mname = matnames[i];
		const Json::Value mat = mats[mname];
		UTF8Material& material = model->materials[mname];

		const Json::Value kd = mat["Kd"];
		const Json::Value map_kd = mat["map_Kd"];

		if (!kd.isNull())
		{
			material.color = Eigen::Vector3f((float)kd[(UINT)0].asDouble(), (float)kd[1].asDouble(), (float)kd[2].asDouble());
		}
		if (!map_kd.isNull())
		{
			// Use the average color from the texture???
			string texfilename = RStrip(texDir, '/') + "/" + map_kd.asString();
			ImageStack::Image teximg = ImageStack::Load::apply(texfilename);
			material.color = Vector3f(0, 0, 0);
			for (int y = 0; y < teximg.height; y++) for (int x = 0; x < teximg.width; x++)
			{
				float* pixel = teximg(x,y);
				material.color += Vector3f(pixel);
			}
			material.color /= (float)(teximg.width*teximg.height);
		}
	}
}

void DecompressAttribs(wchar_t* indata, int instart, int inend, vector<float>& outdata, int outstart, int stride, int decodeOffset, float decodeScale)
{
	wchar_t prev = 0;
	for (int j = instart; j < inend; j++)
	{
		wchar_t code = indata[j];
		prev += (code >> 1) ^ (-(code & 1));
		outdata[outstart] = decodeScale * (prev + decodeOffset);
		outstart += stride;
	}
}

void DecompressIndices(wchar_t* indata, int instart, UINT numIndices, vector<UINT>& outdata, int outstart)
{
	UINT highest = 0;
	for (UINT i = 0; i < numIndices; i++)
	{
		wchar_t code = indata[instart++];
		outdata[outstart++] = highest - code;
		if (code == 0)
		{
			highest++;
		}
	}
}

void DecompressMesh(wchar_t* indata, const Json::Value& meshParams, const vector<int>& decodeOffsets, const vector<float>& decodeScales, vector<float>& outAttribs, vector<UINT>& outIndices)
{
	int stride = decodeScales.size();
	vector<int> attribRange; JsonUtils::ParseIntArrayProp(meshParams["attribRange"], attribRange);
	vector<int> indexRange; JsonUtils::ParseIntArrayProp(meshParams["indexRange"], indexRange);
	int attribStart = attribRange[0];
	int numVerts = attribRange[1];

	// Decode attributes
	int inputOffset = attribStart;
	outAttribs.clear(); outAttribs.resize(stride*numVerts);
	for (int j = 0; j < stride; j++)
	{
		int end = inputOffset + numVerts;
		float decodeScale = decodeScales[j];
		DecompressAttribs(indata, inputOffset, end, outAttribs, j, stride, decodeOffsets[j], decodeScale);
		inputOffset = end;
	}

	// Decode indices
	int indexStart = indexRange[0];
	int numIndices=  3*indexRange[1];
	outIndices.clear(); outIndices.resize(numIndices);
	DecompressIndices(indata, inputOffset, numIndices, outIndices, 0);
}

CommonMesh* CreateMeshFromDataArrays(const vector<float>& attribData, const vector<UINT>& indexData)
{
	CommonMesh* mesh = new CommonMesh;
	mesh->AddVec3Attrib(CommonMesh::NormalAttribName);
	mesh->AddVec2Attrib(CommonMesh::UVAttribName);
	FaceList& faces = mesh->Faces();
	Vec3List& verts = mesh->Vertices();
	Vec3List& norms = mesh->Normals();
	Vec2List& uvs = mesh->UVs();

	UINT numFaces = indexData.size() / 3;
	faces.resize(numFaces);
	for (UINT i = 0; i < numFaces; i++)
	{
		MeshFace& f = faces[i];
		UINT j = 3*i;
		f.i[0] = indexData[j];
		f.i[1] = indexData[j+1];
		f.i[2] = indexData[j+2];
	}

	// I assume that the data contains positions, uvs, and normals (in that order)
	const UINT stride = 8;
	UINT numVerts = attribData.size() / stride;
	verts.resize(numVerts);
	norms.resize(numVerts);
	uvs.resize(numVerts);
	for (UINT i = 0; i < numVerts; i++)
	{
		Vector3f& pos = verts[i];
		Vector2f& uv = uvs[i];
		Vector3f& norm = norms[i];

		UINT j = stride*i;
		pos[0] = attribData[j];
		pos[1] = attribData[j+1];
		pos[2] = attribData[j+2];
		uv[0] = attribData[j+3];
		uv[1] = attribData[j+4];
		norm[0] = attribData[j+5];
		norm[1] = attribData[j+6];
		norm[2] = attribData[j+7];
	}

	return mesh;
}

void ParseComponents(UTF8Model* model, const Json::Value& root, const string& utf8Dir, const vector<int>& decodeOffsets, const vector<float>& decodeScales)
{
	const Json::Value urls = JsonUtils::GetRequiredJsonProp(root, "urls");
	const Json::Value::Members urlnames = urls.getMemberNames();
	for (UINT i = 0; i < urlnames.size(); i++)
	{
		const string& urlname = urlnames[i];
		const Json::Value url = urls[urlname];

		// Read in data from the utf8 file on disk.
		wchar_t* data;
		string fullfilename = RStrip(utf8Dir, '/') + "/" + urlname;
		wifstream wif(fullfilename.c_str(), ios::in | ios::binary);
		if (!wif.is_open())
			FatalError(string("UTF8Model::Load - Could not find utf8 file '" + fullfilename + "'"));
		wif.imbue(locale(locale::empty(), new std::codecvt_utf8<wchar_t,0x10ffff,std::consume_header>));
		wif.seekg(0, ios::end);
		int length = (int)wif.tellg();
		wif.seekg(0, ios::beg);
		data = new wchar_t[length];
		wif.read(data, length);
		wif.close();

		// Decompress all sub-meshes
		for (UINT i = 0; i < url.size(); i++)
		{
			const Json::Value meshparams = url[i];
			vector<float> attribData;
			vector<UINT> indexData;
			DecompressMesh(data, meshparams, decodeOffsets, decodeScales, attribData, indexData);

			// Create and append a new UTF8ModelComponent
			UTF8ModelComponent comp;
			comp.material = &(model->materials[meshparams["material"].asString()]);
			comp.mesh = CreateMeshFromDataArrays(attribData, indexData);
			model->components.push_back(comp);
		}

		delete[] data;
	}
}

void UTF8Model::FreeMemory()
{
	for (UINT i = 0; i < components.size(); i++)
	{
		delete components[i].mesh;
	}

	components.clear();
	materials.clear();
}

void UTF8Model::Load(const string& jsonfilename, const string& utf8Dir, const string& texDir)
{
	FreeMemory();

	ifstream jsonstream(jsonfilename.c_str());
	Json::Value root;
	Json::Reader reader;
	bool parsingSuccessful = reader.parse(jsonstream, root);
	jsonstream.close();
	if (!parsingSuccessful)
	{
		string msg = "UTF8Model::Load - Failed to parse JSON file '" + jsonfilename + "'\n";
		msg += reader.getFormatedErrorMessages();
		FatalError(msg)
	}

	// Parse the list of materials
	ParseMaterials(this, root, texDir);

	const Json::Value decodeParams = JsonUtils::GetRequiredJsonProp(root, "decodeParams");
	const Json::Value _decodeOffsets = JsonUtils::GetRequiredJsonProp(decodeParams, "decodeOffsets");
	const Json::Value _decodeScales = JsonUtils::GetRequiredJsonProp(decodeParams, "decodeScales");
	vector<int> decodeOffsets;
	vector<float> decodeScales;
	JsonUtils::ParseIntArrayProp(_decodeOffsets, decodeOffsets);
	JsonUtils::ParseFloatArrayProp(_decodeScales, decodeScales);

	// Parse the list of components
	ParseComponents(this, root, utf8Dir, decodeOffsets, decodeScales);
}

AlignedBox3f UTF8Model::Bounds() const
{
	// Accumulate bbox for all transformed vertices
	AlignedBox3f box;
	for (UINT i = 0; i < components.size(); i++)
	{
		const Vec3List& verts = components[i].mesh->Vertices();
		for (UINT j = 0; j < verts.size(); j++)
		{
			box.extend(transform.TransformPoint(verts[i]));
		}
	}
	return box;
}

void UTF8Model::Render()
{
	TransformStack::Modelview().Push();
	TransformStack::Modelview().Multiply(transform);
	TransformStack::Modelview().Bind();

	for (UINT i = 0; i < components.size(); i++)
	{
		const UTF8ModelComponent& comp = components[i];
		int colloc = ShaderProgram::CurrentProgram()->GetUniformLocation("Color");
		if (colloc >= 0)
			glUniform3fv(colloc, 1, &(comp.material->color[0]));
		components[i].mesh->Render();
	}

	TransformStack::Modelview().Pop();
}

void UTF8Model::Pick(UINT myIndex)
{
	TransformStack::Modelview().Push();
	TransformStack::Modelview().Multiply(transform);
	TransformStack::Modelview().Bind();

	for (UINT i = 0; i < components.size(); i++)
	{
		Eigen::Vector4f floats = Picker::PackIDs((unsigned short)myIndex, (unsigned short)i);
		int bytesloc = ShaderProgram::CurrentProgram()->GetUniformLocation("IdBytes");
		if (bytesloc >= 0)
			glUniform4fv(bytesloc, 1, &floats[0]);
		components[i].mesh->Render();
	}

	TransformStack::Modelview().Pop();
}