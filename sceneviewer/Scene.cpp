#include "Scene.h"


void Scene::LoadFromWSS(WSSScene* wss)
{
	FreeMemory();

	// Convert the models one by one, also
	// assigning indices
	models.resize(wss->models.size());
	for (UINT i = 0; i < models.size(); i++)
	{
		Model* model = new Model;
		model->LoadFromUTF8(wss->models[i]);
		model->index = (int)i;

		models[i] = model;
	}
}

void Scene::FreeMemory()
{
	for (UINT i = 0; i < models.size(); i++)
		delete models[i];
}

Scene::~Scene()
{
	FreeMemory();
}

void Scene::Render()
{
	for (UINT i = 0; i < models.size(); i++)
	{
		models[i]->Render();
	}
}

void Scene::Pick()
{
	for (UINT i = 0; i < models.size(); i++)
	{
		models[i]->Pick();
	}
}
